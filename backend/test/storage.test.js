const test = require('node:test');
const assert = require('node:assert/strict');

const { createDatabaseItemStore } = require('../src/plaid');

test('database item store persists and reloads Plaid items', async () => {
  let stored = null;
  const queries = [];
  const db = {
    async query(sql, params = []) {
      queries.push({ sql, params });
      if (/^CREATE TABLE/i.test(sql.trim())) return { rows: [] };
      if (/^SELECT/i.test(sql.trim())) return { rows: stored == null ? [] : [{ value: stored }] };
      if (/^INSERT/i.test(sql.trim())) {
        stored = JSON.parse(params[0]);
        return { rows: [] };
      }
      throw new Error(`Unexpected SQL: ${sql}`);
    }
  };

  const store = createDatabaseItemStore(db);
  const items = [{ itemId: 'item_1', accessToken: 'access-token', label: 'Bank', createdAt: '2026-09-11T00:00:00Z' }];

  await store.save(items);
  assert.deepEqual(await store.load(), items);
  assert.ok(queries.some(({ sql }) => /^CREATE TABLE/i.test(sql.trim())));
  assert.ok(queries.some(({ sql }) => /^INSERT/i.test(sql.trim())));
  assert.ok(queries.some(({ sql }) => /^SELECT/i.test(sql.trim())));
});
