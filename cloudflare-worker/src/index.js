const encoder = new TextEncoder();
const decoder = new TextDecoder();

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'authorization, content-type',
      'access-control-allow-methods': 'GET,POST,DELETE,OPTIONS'
    }
  });
}

function requireAuth(request, env) {
  const expected = env.BILLNEST_API_KEY || '';
  const actual = request.headers.get('authorization') || '';
  if (!expected || actual !== `Bearer ${expected}`) {
    throw Object.assign(new Error('Unauthorized'), { status: 401 });
  }
}

function plaidBaseUrl(envName) {
  return String(envName || 'sandbox').toLowerCase() === 'production'
    ? 'https://production.plaid.com'
    : 'https://sandbox.plaid.com';
}

async function plaidPost(env, endpoint, body) {
  if (!env.PLAID_CLIENT_ID) throw new Error('PLAID_CLIENT_ID is not configured');
  if (!env.PLAID_SECRET) throw new Error('PLAID_SECRET is not configured');

  const response = await fetch(`${plaidBaseUrl(env.PLAID_ENV)}${endpoint}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      client_id: env.PLAID_CLIENT_ID,
      secret: env.PLAID_SECRET,
      ...body
    })
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
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function hexToBytes(hex) {
  if (!/^[0-9a-fA-F]{64}$/.test(hex || '')) {
    throw new Error('TOKEN_ENCRYPTION_KEY must be a 64-character hex string');
  }
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

async function tokenCryptoKey(env) {
  return crypto.subtle.importKey(
    'raw',
    hexToBytes(env.TOKEN_ENCRYPTION_KEY),
    { name: 'AES-GCM' },
    false,
    ['encrypt', 'decrypt']
  );
}

async function encryptToken(env, token) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await tokenCryptoKey(env);
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, encoder.encode(token)));
  return `${bytesToBase64(iv)}.${bytesToBase64(cipher)}`;
}

async function decryptToken(env, packed) {
  const [ivPart, cipherPart] = String(packed || '').split('.');
  if (!ivPart || !cipherPart) throw new Error('Stored Plaid token is invalid');
  const key = await tokenCryptoKey(env);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: base64ToBytes(ivPart) },
    key,
    base64ToBytes(cipherPart)
  );
  return decoder.decode(plain);
}

function normalizeAccounts(accounts = [], itemId = null, connectionLabel = null) {
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

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

async function route(request, env) {
  const url = new URL(request.url);

  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: json({}).headers });
  if (request.method === 'GET' && url.pathname === '/health') {
    return json({ ok: true, service: 'billnest-cloudflare', storage: 'd1' });
  }

  if (!url.pathname.startsWith('/api/')) return json({ error: 'Not found' }, 404);
  requireAuth(request, env);

  if (request.method === 'POST' && url.pathname === '/api/plaid/link-token') {
    const result = await plaidPost(env, '/link/token/create', {
      client_name: 'BillNest',
      language: 'en',
      country_codes: ['US'],
      products: ['transactions'],
      android_package_name: 'com.baylee.billnest',
      user: { client_user_id: 'billnest-personal' }
    });
    return json({ linkToken: result.link_token, expiration: result.expiration });
  }

  if (request.method === 'POST' && url.pathname === '/api/plaid/exchange') {
    const body = await readJson(request);
    if (!body.publicToken) return json({ error: 'publicToken is required' }, 400);

    const result = await plaidPost(env, '/item/public_token/exchange', {
      public_token: body.publicToken
    });
    const encrypted = await encryptToken(env, result.access_token);
    const createdAt = new Date().toISOString();

    await env.DB.prepare(`
      INSERT INTO plaid_items (item_id, access_token_enc, label, created_at)
      VALUES (?1, ?2, ?3, ?4)
      ON CONFLICT(item_id) DO UPDATE SET
        access_token_enc = excluded.access_token_enc,
        label = excluded.label
    `).bind(result.item_id, encrypted, body.label || null, createdAt).run();

    return json({ itemId: result.item_id });
  }

  if (request.method === 'GET' && url.pathname === '/api/plaid/accounts') {
    const rows = await env.DB.prepare(
      'SELECT item_id, access_token_enc, label, created_at FROM plaid_items ORDER BY created_at ASC'
    ).all();

    const accounts = [];
    for (const item of rows.results || []) {
      const accessToken = await decryptToken(env, item.access_token_enc);
      const result = await plaidPost(env, '/accounts/balance/get', { access_token: accessToken });
      accounts.push(...normalizeAccounts(result.accounts, item.item_id, item.label || null));
    }
    return json({ accounts, connectedItems: (rows.results || []).length });
  }

  if (request.method === 'GET' && url.pathname === '/api/plaid/items') {
    const rows = await env.DB.prepare(
      'SELECT item_id, label, created_at FROM plaid_items ORDER BY created_at ASC'
    ).all();
    return json({
      items: (rows.results || []).map((row) => ({
        itemId: row.item_id,
        label: row.label,
        createdAt: row.created_at
      }))
    });
  }

  if (request.method === 'DELETE' && url.pathname.startsWith('/api/plaid/items/')) {
    const itemId = decodeURIComponent(url.pathname.slice('/api/plaid/items/'.length));
    const row = await env.DB.prepare('SELECT access_token_enc FROM plaid_items WHERE item_id = ?1').bind(itemId).first();
    if (row?.access_token_enc) {
      const accessToken = await decryptToken(env, row.access_token_enc);
      await plaidPost(env, '/item/remove', { access_token: accessToken });
    }
    await env.DB.prepare('DELETE FROM plaid_items WHERE item_id = ?1').bind(itemId).run();
    return json({ ok: true });
  }

  return json({ error: 'Not found' }, 404);
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      return json({
        error: error.message || 'Server error',
        plaidErrorCode: error.plaid?.error_code || null
      }, error.status || 500);
    }
  }
};

export { normalizeAccounts, encryptToken, decryptToken };
