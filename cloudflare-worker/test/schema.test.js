import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

function fakeD1() {
  return {
    exec() {
      throw new Error('D1_EXEC_ERROR: multiline exec should not be used for schema setup');
    },
    prepare(sql) {
      const text = String(sql).trim();
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

test('GET /api/plaid/items initializes D1 schema without multiline exec', async () => {
  const response = await worker.fetch(
    new Request('https://billnest.test/api/plaid/items', {
      headers: { authorization: 'Bearer test-key' }
    }),
    {
      DB: fakeD1(),
      BILLNEST_API_KEY: 'test-key'
    }
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { items: [] });
});
