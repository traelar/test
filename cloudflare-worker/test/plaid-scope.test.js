import test from 'node:test';
import assert from 'node:assert/strict';
import { createPlaidService } from '../src/plaid.js';

class MemoryPlaidStore {
  constructor() {
    this.items = new Map();
    this.mappings = new Map();
  }
  async upsertPlaidItem(item) { this.items.set(item.itemId, { ...item }); }
  async mapPlaidItemToHousehold(mapping) { this.mappings.set(mapping.itemId, { ...mapping }); }
  async findPlaidItemAny(itemId) { return this.items.get(itemId) || null; }
  async findPlaidItemForHousehold(householdId, itemId) {
    const mapping = this.mappings.get(itemId);
    if (!mapping || mapping.householdId !== householdId) return null;
    const item = this.items.get(itemId);
    return item ? { ...item, ...mapping } : null;
  }
  async listPlaidItemsAll() { return [...this.items.values()]; }
  async listPlaidItemsForHousehold(householdId) {
    return [...this.mappings.values()]
      .filter((mapping) => mapping.householdId === householdId)
      .map((mapping) => ({ ...this.items.get(mapping.itemId), ...mapping }));
  }
  async deletePlaidItem(itemId) {
    this.items.delete(itemId);
    this.mappings.delete(itemId);
  }
}

const ownerContext = { mode: 'session', userId: 'owner-1', householdId: 'house-1', role: 'owner' };
const memberContext = { mode: 'session', userId: 'member-1', householdId: 'house-1', role: 'member' };
const otherOwnerContext = { mode: 'session', userId: 'owner-2', householdId: 'house-2', role: 'owner' };
const legacyContext = { mode: 'legacy' };

function service(store = new MemoryPlaidStore()) {
  const plaidCalls = [];
  return {
    store,
    plaidCalls,
    plaid: createPlaidService(store, {
      now: () => new Date('2026-09-12T00:00:00.000Z'),
      encryptToken: async (token) => `encrypted:${token}`,
      decryptToken: async (token) => token.replace('encrypted:', ''),
      plaidPost: async (_env, endpoint, body) => {
        plaidCalls.push({ endpoint, body });
        return { ok: true };
      }
    })
  };
}

test('member may attach a new Plaid item to the shared household', async () => {
  const { plaid } = service();
  const saved = await plaid.saveExchangedItem(memberContext, {
    itemId: 'item-1',
    accessToken: 'access-1',
    label: 'Fiance Bank'
  });
  assert.equal(saved.householdId, memberContext.householdId);
  assert.equal(saved.connectedByUserId, memberContext.userId);
});

test('member cannot disconnect a Plaid item', async () => {
  const { plaid } = service();
  await plaid.saveExchangedItem(memberContext, { itemId: 'item-1', accessToken: 'access-1', label: 'Fiance Bank' });
  await assert.rejects(() => plaid.removeItem(memberContext, 'item-1', {}), (error) => error.status === 403);
});

test('owner cannot access an item mapped to another household', async () => {
  const { plaid } = service();
  await plaid.saveExchangedItem(otherOwnerContext, { itemId: 'other-house-item', accessToken: 'access-2', label: 'Other Bank' });
  await assert.rejects(() => plaid.getItemAccessToken(ownerContext, 'other-house-item'), (error) => error.status === 404);
});

test('household item listing returns only that household items', async () => {
  const { plaid } = service();
  await plaid.saveExchangedItem(ownerContext, { itemId: 'mine', accessToken: 'a1', label: 'Mine' });
  await plaid.saveExchangedItem(otherOwnerContext, { itemId: 'theirs', accessToken: 'a2', label: 'Theirs' });
  const items = await plaid.listItems(ownerContext);
  assert.deepEqual(items.map((item) => item.itemId), ['mine']);
});

test('legacy caller retains all-items access during migration', async () => {
  const { plaid } = service();
  await plaid.saveExchangedItem(ownerContext, { itemId: 'one', accessToken: 'a1', label: 'One' });
  await plaid.saveExchangedItem(otherOwnerContext, { itemId: 'two', accessToken: 'a2', label: 'Two' });
  const items = await plaid.listItems(legacyContext);
  assert.deepEqual(items.map((item) => item.itemId).sort(), ['one', 'two']);
});

test('owner disconnect removes only a same-household item after calling Plaid item remove', async () => {
  const { plaid, plaidCalls, store } = service();
  await plaid.saveExchangedItem(ownerContext, { itemId: 'mine', accessToken: 'access-1', label: 'Mine' });
  const result = await plaid.removeItem(ownerContext, 'mine', {});
  assert.deepEqual(result, { ok: true });
  assert.equal(await store.findPlaidItemAny('mine'), null);
  assert.deepEqual(plaidCalls, [{ endpoint: '/item/remove', body: { access_token: 'access-1' } }]);
});

test('account refresh keeps healthy accounts and reports an item that needs reconnect', async () => {
  const store = new MemoryPlaidStore();
  const plaid = createPlaidService(store, {
    now: () => new Date('2026-09-12T00:00:00.000Z'),
    encryptToken: async (token) => `encrypted:${token}`,
    decryptToken: async (token) => token.replace('encrypted:', ''),
    plaidPost: async (_env, endpoint, body) => {
      if (endpoint === '/accounts/balance/get' && body.access_token === 'access-broken') {
        const error = new Error('the login details of this item have changed');
        error.plaid = { error_code: 'ITEM_LOGIN_REQUIRED' };
        throw error;
      }
      if (endpoint === '/accounts/balance/get') {
        return {
          accounts: [{
            account_id: 'acct-healthy',
            name: 'Checking',
            type: 'depository',
            subtype: 'checking',
            balances: { current: 120, available: 100 }
          }]
        };
      }
      return { ok: true };
    }
  });

  await plaid.saveExchangedItem(ownerContext, { itemId: 'healthy', accessToken: 'access-healthy', label: 'Healthy Bank' });
  await plaid.saveExchangedItem(ownerContext, { itemId: 'broken', accessToken: 'access-broken', label: 'Broken Bank' });

  const result = await plaid.fetchAccounts(ownerContext, {});
  assert.equal(result.accounts.length, 1);
  assert.equal(result.accounts[0].accountId, 'acct-healthy');
  assert.deepEqual(result.issues, [{
    itemId: 'broken',
    label: 'Broken Bank',
    errorCode: 'ITEM_LOGIN_REQUIRED',
    message: 'the login details of this item have changed',
    requiresReconnect: true
  }]);
});

test('reconnect creates a Plaid update-mode link token for the existing item', async () => {
  const store = new MemoryPlaidStore();
  const calls = [];
  const plaid = createPlaidService(store, {
    now: () => new Date('2026-09-12T00:00:00.000Z'),
    encryptToken: async (token) => `encrypted:${token}`,
    decryptToken: async (token) => token.replace('encrypted:', ''),
    plaidPost: async (_env, endpoint, body) => {
      calls.push({ endpoint, body });
      if (endpoint === '/link/token/create') return { link_token: 'update-link-token', expiration: 'later' };
      return { ok: true };
    }
  });

  await plaid.saveExchangedItem(ownerContext, { itemId: 'mine', accessToken: 'access-1', label: 'My Bank' });
  const result = await plaid.createUpdateLinkToken(ownerContext, {}, 'mine');

  assert.equal(result.link_token, 'update-link-token');
  assert.equal(calls.at(-1).endpoint, '/link/token/create');
  assert.equal(calls.at(-1).body.access_token, 'access-1');
  assert.equal('products' in calls.at(-1).body, false);
});
