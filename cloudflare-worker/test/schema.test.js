import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

function fakeD1(preparedSql) {
  return {
    exec() {
      throw new Error('D1_EXEC_ERROR: multiline exec should not be used for schema setup');
    },
    async batch(statements) {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      return results;
    },
    prepare(sql) {
      const text = String(sql).trim();
      preparedSql.push(text);
      return {
        bind() { return this; },
        async run() { return { success: true }; },
        async all() {
          if (text.startsWith('SELECT item_id, label, created_at FROM plaid_items')) {
            return { results: [] };
          }
          return { results: [] };
        },
        async first() { return null; }
      };
    }
  };
}

test('GET /api/plaid/items initializes the household schema without multiline exec', async () => {
  const preparedSql = [];
  const response = await worker.fetch(
    new Request('https://billnest.test/api/plaid/items', {
      headers: { authorization: 'Bearer test-key' }
    }),
    {
      DB: fakeD1(preparedSql),
      BILLNEST_API_KEY: 'test-key'
    }
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { items: [] });

  const requiredFragments = [
    'CREATE TABLE IF NOT EXISTS users',
    'CREATE TABLE IF NOT EXISTS sessions',
    'CREATE TABLE IF NOT EXISTS households',
    'CREATE TABLE IF NOT EXISTS household_members',
    'CREATE TABLE IF NOT EXISTS household_invites',
    'CREATE TABLE IF NOT EXISTS plaid_item_households',
    'CREATE TABLE IF NOT EXISTS finance_records',
    'CREATE TABLE IF NOT EXISTS sync_events',
    'CREATE TABLE IF NOT EXISTS auth_rate_limits'
  ];

  assert.equal(
    requiredFragments.every(fragment => preparedSql.some(sql => sql.includes(fragment))),
    true,
    `Missing schema statements. Prepared SQL: ${preparedSql.join(' | ')}`
  );
});
