import test from 'node:test';
import assert from 'node:assert/strict';
import { createPlaidService } from '../src/plaid.js';

const context = { mode: 'session', householdId: 'home-1', userId: 'user-1', role: 'owner' };

test('transaction history combines healthy items and reports broken items', async () => {
  const store = {
    async listPlaidItemsForHousehold() {
      return [
        { itemId: 'good', accessTokenEnc: 'good-token', label: 'Good Bank' },
        { itemId: 'broken', accessTokenEnc: 'broken-token', label: 'Broken Bank' }
      ];
    }
  };
  const plaid = createPlaidService(store, {
    now: () => new Date('2026-09-12T12:00:00Z'),
    decryptToken: async (value) => value,
    plaidPost: async (_env, endpoint, body) => {
      assert.equal(endpoint, '/transactions/get');
      if (body.access_token === 'broken-token') {
        const error = new Error('login changed');
        error.plaid = { error_code: 'ITEM_LOGIN_REQUIRED' };
        throw error;
      }
      return {
        total_transactions: 2,
        transactions: [
          { transaction_id: 'expense-1', account_id: 'account-1', name: 'Grocery', amount: 42.25, date: '2026-09-10', pending: false, personal_finance_category: { primary: 'FOOD_AND_DRINK' } },
          { transaction_id: 'income-1', account_id: 'account-1', name: 'ACME Payroll', amount: -1200.55, date: '2026-09-05', pending: false, personal_finance_category: { primary: 'INCOME' } }
        ]
      };
    }
  });

  const result = await plaid.fetchTransactions(context, {});

  assert.equal(result.transactions.length, 2);
  assert.equal(result.transactions[1].income, true);
  assert.equal(result.transactions[1].amount, 1200.55);
  assert.equal(result.issues[0].requiresReconnect, true);
});
