import test from 'node:test';
import assert from 'node:assert/strict';
import { createTransactionService } from '../src/transactions.js';

class MemoryTransactionStore {
  constructor() {
    this.items = [{ itemId: 'item-1', accessTokenEnc: 'enc-access', label: 'Bank' }];
    this.cursor = null;
    this.rows = new Map();
    this.issues = new Map();
  }
  async listItems(householdId) { return householdId === 'house-1' ? this.items : []; }
  async getCursor() { return this.cursor; }
  async applyPage({ nextCursor, added, modified, removed }) {
    for (const tx of [...added, ...modified]) this.rows.set(tx.transaction_id, tx);
    for (const tx of removed) this.rows.delete(tx.transaction_id);
    this.cursor = nextCursor;
  }
  async recordIssue(itemId, code, message) { this.issues.set(itemId, { code, message }); }
  async clearIssue(itemId) { this.issues.delete(itemId); }
  async listTransactions(householdId) {
    return householdId === 'house-1' ? [...this.rows.values()] : [];
  }
}

const context = { mode: 'session', householdId: 'house-1', userId: 'user-1' };

test('transaction sync persists added rows and advances the Plaid cursor', async () => {
  const store = new MemoryTransactionStore();
  const calls = [];
  const service = createTransactionService(store, {
    decryptToken: async () => 'access-1',
    plaidPost: async (_env, endpoint, body) => {
      calls.push({ endpoint, body });
      return {
        added: [{ transaction_id: 'tx-1', account_id: 'acct-1', name: 'Groceries', amount: 42.5, date: '2026-09-11', pending: false }],
        modified: [],
        removed: [],
        next_cursor: 'cursor-1',
        has_more: false
      };
    }
  });

  const result = await service.syncHousehold(context, {});

  assert.equal(result.added, 1);
  assert.equal(store.cursor, 'cursor-1');
  assert.equal(store.rows.has('tx-1'), true);
  assert.equal(calls[0].endpoint, '/transactions/sync');
  assert.equal(calls[0].body.cursor, undefined);
});

test('subsequent transaction sync resumes from the saved cursor', async () => {
  const store = new MemoryTransactionStore();
  store.cursor = 'cursor-before';
  let usedCursor = null;
  const service = createTransactionService(store, {
    decryptToken: async () => 'access-1',
    plaidPost: async (_env, _endpoint, body) => {
      usedCursor = body.cursor;
      return { added: [], modified: [], removed: [], next_cursor: 'cursor-after', has_more: false };
    }
  });

  await service.syncHousehold(context, {});
  assert.equal(usedCursor, 'cursor-before');
  assert.equal(store.cursor, 'cursor-after');
});

test('login-required item becomes an issue instead of failing the whole transaction sync', async () => {
  const store = new MemoryTransactionStore();
  const service = createTransactionService(store, {
    decryptToken: async () => 'access-1',
    plaidPost: async () => {
      const error = new Error('login required');
      error.plaid = { error_code: 'ITEM_LOGIN_REQUIRED' };
      throw error;
    }
  });

  const result = await service.syncHousehold(context, {});
  assert.equal(result.issues.length, 1);
  assert.equal(result.issues[0].requiresReconnect, true);
  assert.equal(store.issues.get('item-1').code, 'ITEM_LOGIN_REQUIRED');
});
