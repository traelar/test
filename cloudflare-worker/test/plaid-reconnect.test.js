import test from 'node:test';
import assert from 'node:assert/strict';
import { createPlaidService } from '../src/plaid.js';

const context = {
  mode: 'session',
  userId: 'owner-1',
  householdId: 'house-1',
  role: 'owner'
};

class MemoryPlaidStore {
  constructor(items = []) {
    this.items = items;
  }

  async listPlaidItemsForHousehold() {
    return this.items;
  }

  async findPlaidItemForHousehold(_householdId, itemId) {
    return this.items.find((item) => item.itemId === itemId) || null;
  }
}

function plaidError(code, message = code) {
  const error = new Error(message);
  error.plaid = { error_code: code, error_message: message };
  return error;
}

test('one ITEM_LOGIN_REQUIRED item does not prevent healthy bank accounts from loading', async () => {
  const store = new MemoryPlaidStore([
    { itemId: 'good-item', label: 'Checking Bank', accessTokenEnc: 'good-token' },
    { itemId: 'bad-item', label: 'Savings Bank', accessTokenEnc: 'bad-token' }
  ]);

  const service = createPlaidService(store, {
    decryptToken: async (packed) => packed,
    plaidPost: async (_env, endpoint, body) => {
      assert.equal(endpoint, '/accounts/balance/get');
      if (body.access_token === 'bad-token') {
        throw plaidError('ITEM_LOGIN_REQUIRED', 'the login details of this item have changed');
      }
      return {
        accounts: [{
          account_id: 'acct-1',
          name: 'Everyday Checking',
          mask: '1234',
          type: 'depository',
          subtype: 'checking',
          balances: { current: 800, available: 750 }
        }]
      };
    }
  });

  const result = await service.fetchAccounts(context, {});

  assert.equal(result.accounts.length, 1);
  assert.equal(result.accounts[0].itemId, 'good-item');
  assert.equal(result.connectedItems, 2);
  assert.equal(result.issues.length, 1);
  assert.equal(result.issues[0].itemId, 'bad-item');
  assert.equal(result.issues[0].errorCode, 'ITEM_LOGIN_REQUIRED');
  assert.equal(result.issues[0].requiresReconnect, true);
});

test('update mode creates a link token from the existing access token', async () => {
  const store = new MemoryPlaidStore([
    { itemId: 'item-1', label: 'My Bank', accessTokenEnc: 'existing-access-token' }
  ]);
  let requestBody = null;

  const service = createPlaidService(store, {
    decryptToken: async (packed) => packed,
    plaidPost: async (_env, endpoint, body) => {
      assert.equal(endpoint, '/link/token/create');
      requestBody = body;
      return { link_token: 'link-update-token', expiration: 'later' };
    }
  });

  const result = await service.createUpdateLinkToken(context, {}, 'item-1');

  assert.equal(result.link_token, 'link-update-token');
  assert.equal(requestBody.access_token, 'existing-access-token');
  assert.equal(requestBody.android_package_name, 'com.baylee.billnest');
  assert.equal(Object.hasOwn(requestBody, 'products'), false);
});

test('non-Plaid failures still fail the accounts request instead of being hidden', async () => {
  const store = new MemoryPlaidStore([
    { itemId: 'item-1', label: 'My Bank', accessTokenEnc: 'broken-ciphertext' }
  ]);

  const service = createPlaidService(store, {
    decryptToken: async () => { throw new Error('Stored Plaid token is invalid'); },
    plaidPost: async () => { throw new Error('should not be called'); }
  });

  await assert.rejects(
    () => service.fetchAccounts(context, {}),
    /Stored Plaid token is invalid/
  );
});
