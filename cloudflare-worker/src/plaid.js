import { httpError, json, readJson } from './http.js';
import { D1Store } from './store.js';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

const RECONNECT_ERROR_CODES = new Set([
  'ITEM_LOGIN_REQUIRED',
  'PENDING_EXPIRATION',
  'PENDING_DISCONNECT'
]);

export function plaidBaseUrl(envName) {
  return String(envName || 'sandbox').toLowerCase() === 'production'
    ? 'https://production.plaid.com'
    : 'https://sandbox.plaid.com';
}

export async function plaidPost(env, endpoint, body) {
  if (!env.PLAID_CLIENT_ID) throw new Error('PLAID_CLIENT_ID is not configured');
  if (!env.PLAID_SECRET) throw new Error('PLAID_SECRET is not configured');
  const response = await fetch(`${plaidBaseUrl(env.PLAID_ENV)}${endpoint}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ client_id: env.PLAID_CLIENT_ID, secret: env.PLAID_SECRET, ...body })
  });
  const data = await response.json();
  if (!response.ok || data.error_code) {
    const error = new Error(data.error_message || data.error_code || `Plaid error ${response.status}`);
    error.plaid = data;
    throw error;
  }
  return data;
}

function bytesToBase64(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function hexToBytes(hex) {
  if (!/^[0-9a-fA-F]{64}$/.test(hex || '')) throw new Error('TOKEN_ENCRYPTION_KEY must be a 64-character hex string');
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i += 1) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

async function tokenCryptoKey(env) {
  return crypto.subtle.importKey('raw', hexToBytes(env.TOKEN_ENCRYPTION_KEY), { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

export async function encryptToken(env, token) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await tokenCryptoKey(env);
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, encoder.encode(token)));
  return `${bytesToBase64(iv)}.${bytesToBase64(cipher)}`;
}

export async function decryptToken(env, packed) {
  const [ivPart, cipherPart] = String(packed || '').split('.');
  if (!ivPart || !cipherPart) throw new Error('Stored Plaid token is invalid');
  const key = await tokenCryptoKey(env);
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: base64ToBytes(ivPart) }, key, base64ToBytes(cipherPart));
  return decoder.decode(plain);
}

export function normalizeAccounts(accounts = [], itemId = null, connectionLabel = null) {
  return accounts.map((account) => ({
    accountId: account.account_id,
    name: account.name,
    officialName: account.official_name || null,
    mask: account.mask || '',
    type: account.type || '',
    subtype: account.subtype || '',
    current: Number(account.balances?.current ?? 0),
    available: account.balances?.available == null ? null : Number(account.balances.available),
    itemId,
    connectionLabel
  }));
}

function issueFromPlaidError(item, error) {
  const errorCode = String(error?.plaid?.error_code || 'PLAID_ERROR');
  return {
    itemId: item.itemId,
    label: item.label || null,
    errorCode,
    message: error?.message || errorCode,
    requiresReconnect: RECONNECT_ERROR_CODES.has(errorCode)
  };
}

export function createPlaidService(store, options = {}) {
  const nowProvider = options.now || (() => new Date());
  const encrypt = options.encryptToken || ((token, env) => encryptToken(env, token));
  const decrypt = options.decryptToken || ((packed, env) => decryptToken(env, packed));
  const post = options.plaidPost || plaidPost;

  function nowIso() {
    const value = nowProvider();
    return (value instanceof Date ? value : new Date(value)).toISOString();
  }

  async function saveExchangedItem(context, { itemId, accessToken, label }, env = {}) {
    const encrypted = await encrypt(accessToken, env);
    const createdAt = nowIso();
    await store.upsertPlaidItem({ itemId, accessTokenEnc: encrypted, label: label || null, createdAt });
    if (context.mode === 'session') {
      await store.mapPlaidItemToHousehold({
        itemId,
        householdId: context.householdId,
        connectedByUserId: context.userId,
        createdAt
      });
      return { itemId, householdId: context.householdId, connectedByUserId: context.userId };
    }
    return { itemId, householdId: null, connectedByUserId: null };
  }

  async function getItemRecord(context, itemId) {
    const item = context.mode === 'legacy'
      ? await store.findPlaidItemAny(itemId)
      : await store.findPlaidItemForHousehold(context.householdId, itemId);
    if (!item) throw httpError(404, 'Bank connection not found');
    return item;
  }

  async function getItemAccessToken(context, itemId, env = {}) {
    const item = await getItemRecord(context, itemId);
    return decrypt(item.accessTokenEnc, env);
  }

  async function listItemRecords(context) {
    return context.mode === 'legacy'
      ? store.listPlaidItemsAll()
      : store.listPlaidItemsForHousehold(context.householdId);
  }

  async function listItems(context) {
    const rows = await listItemRecords(context);
    return rows.map((row) => ({ itemId: row.itemId, label: row.label, createdAt: row.createdAt }));
  }

  async function createLinkToken(context, env) {
    return post(env, '/link/token/create', {
      client_name: 'BillNest',
      language: 'en',
      country_codes: ['US'],
      products: ['transactions'],
      android_package_name: 'com.baylee.billnest',
      user: { client_user_id: 'billnest-personal' }
    });
  }

  async function createUpdateLinkToken(context, env, itemId) {
    const accessToken = await getItemAccessToken(context, itemId, env);
    return post(env, '/link/token/create', {
      client_name: 'BillNest',
      language: 'en',
      country_codes: ['US'],
      android_package_name: 'com.baylee.billnest',
      user: { client_user_id: 'billnest-personal' },
      access_token: accessToken
    });
  }

  async function exchangePublicToken(context, env, publicToken, label) {
    if (!publicToken) throw httpError(400, 'publicToken is required');
    const result = await post(env, '/item/public_token/exchange', { public_token: publicToken });
    return saveExchangedItem(context, { itemId: result.item_id, accessToken: result.access_token, label }, env);
  }

  async function fetchAccounts(context, env) {
    const items = await listItemRecords(context);
    const accounts = [];
    const issues = [];
    for (const item of items) {
      try {
        const accessToken = await decrypt(item.accessTokenEnc, env);
        const result = await post(env, '/accounts/balance/get', { access_token: accessToken });
        accounts.push(...normalizeAccounts(result.accounts, item.itemId, item.label || null));
      } catch (error) {
        if (!error?.plaid) throw error;
        issues.push(issueFromPlaidError(item, error));
      }
    }
    return { accounts, connectedItems: items.length, issues };
  }

  async function removeItem(context, itemId, env) {
    if (context.mode === 'session' && context.role !== 'owner') {
      throw httpError(403, 'Only the household owner can disconnect a bank');
    }
    const accessToken = await getItemAccessToken(context, itemId, env);
    await post(env, '/item/remove', { access_token: accessToken });
    await store.deletePlaidItem(itemId);
    return { ok: true };
  }

  return {
    saveExchangedItem,
    getItemAccessToken,
    listItems,
    createLinkToken,
    createUpdateLinkToken,
    exchangePublicToken,
    fetchAccounts,
    removeItem
  };
}

export async function handlePlaidHttp(request, env, context, store = new D1Store(env.DB)) {
  const url = new URL(request.url);
  if (!url.pathname.startsWith('/api/plaid/')) return null;
  const service = createPlaidService(store);

  if (request.method === 'POST' && url.pathname === '/api/plaid/link-token') {
    const result = await service.createLinkToken(context, env);
    return json({ linkToken: result.link_token, expiration: result.expiration });
  }
  if (request.method === 'POST' && url.pathname === '/api/plaid/exchange') {
    const body = await readJson(request);
    const saved = await service.exchangePublicToken(context, env, body.publicToken, body.label || null);
    return json({ itemId: saved.itemId });
  }
  if (request.method === 'GET' && url.pathname === '/api/plaid/accounts') {
    return json(await service.fetchAccounts(context, env));
  }
  if (request.method === 'GET' && url.pathname === '/api/plaid/items') {
    return json({ items: await service.listItems(context) });
  }
  if (request.method === 'POST' && url.pathname.startsWith('/api/plaid/items/') && url.pathname.endsWith('/link-token')) {
    const prefix = '/api/plaid/items/';
    const suffix = '/link-token';
    const itemId = decodeURIComponent(url.pathname.slice(prefix.length, -suffix.length));
    if (!itemId) return json({ error: 'Bank connection not found' }, 404);
    const result = await service.createUpdateLinkToken(context, env, itemId);
    return json({ linkToken: result.link_token, expiration: result.expiration });
  }
  if (request.method === 'DELETE' && url.pathname.startsWith('/api/plaid/items/')) {
    const itemId = decodeURIComponent(url.pathname.slice('/api/plaid/items/'.length));
    return json(await service.removeItem(context, itemId, env));
  }
  return json({ error: 'Not found' }, 404);
}
