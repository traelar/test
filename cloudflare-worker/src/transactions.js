import { json } from './http.js';
import { decryptToken, plaidPost } from './plaid.js';

const MAX_PAGES_PER_ITEM = 20;
const PAGE_COUNT = 500;

function chunks(items, size = 50) {
  const result = [];
  for (let index = 0; index < items.length; index += size) result.push(items.slice(index, index + size));
  return result;
}

export class D1TransactionStore {
  constructor(db) { this.db = db; }

  async listItems(householdId) {
    const rows = await this.db.prepare(`
      SELECT p.item_id, p.access_token_enc, p.label
      FROM plaid_items p
      JOIN plaid_item_households ph ON ph.item_id = p.item_id
      WHERE ph.household_id = ?1
      ORDER BY p.created_at ASC
    `).bind(householdId).all();
    return (rows.results || []).map((row) => ({ itemId: row.item_id, accessTokenEnc: row.access_token_enc, label: row.label }));
  }

  async getCursor(itemId) {
    const row = await this.db.prepare('SELECT cursor FROM plaid_transaction_cursors WHERE item_id = ?1 LIMIT 1').bind(itemId).first();
    return row?.cursor || null;
  }

  async applyPage({ householdId, itemId, nextCursor, added, modified, removed, nowIso }) {
    const changed = [...(added || []), ...(modified || [])];
    for (const part of chunks(changed)) {
      if (!part.length) continue;
      await this.db.batch(part.map((tx) => this.db.prepare(`
        INSERT INTO plaid_transactions (
          transaction_id, household_id, item_id, account_id, name, merchant_name,
          amount, iso_date, pending, category_primary, category_detailed, updated_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
        ON CONFLICT(transaction_id) DO UPDATE SET
          household_id = excluded.household_id,
          item_id = excluded.item_id,
          account_id = excluded.account_id,
          name = excluded.name,
          merchant_name = excluded.merchant_name,
          amount = excluded.amount,
          iso_date = excluded.iso_date,
          pending = excluded.pending,
          category_primary = excluded.category_primary,
          category_detailed = excluded.category_detailed,
          updated_at = excluded.updated_at
      `).bind(
        tx.transaction_id,
        householdId,
        itemId,
        tx.account_id || '',
        tx.name || tx.merchant_name || 'Transaction',
        tx.merchant_name || null,
        Number(tx.amount || 0),
        tx.date || String(tx.datetime || '').slice(0, 10),
        tx.pending ? 1 : 0,
        tx.personal_finance_category?.primary || null,
        tx.personal_finance_category?.detailed || null,
        nowIso
      )));
    }

    const removedIds = (removed || []).map((value) => value.transaction_id).filter(Boolean);
    for (const part of chunks(removedIds)) {
      if (!part.length) continue;
      await this.db.batch(part.map((id) => this.db.prepare(
        'DELETE FROM plaid_transactions WHERE household_id = ?1 AND item_id = ?2 AND transaction_id = ?3'
      ).bind(householdId, itemId, id)));
    }

    await this.db.prepare(`
      INSERT INTO plaid_transaction_cursors (item_id, cursor, last_synced_at, last_error_code, last_error_message)
      VALUES (?1, ?2, ?3, NULL, NULL)
      ON CONFLICT(item_id) DO UPDATE SET
        cursor = excluded.cursor,
        last_synced_at = excluded.last_synced_at,
        last_error_code = NULL,
        last_error_message = NULL
    `).bind(itemId, nextCursor || null, nowIso).run();
  }

  async recordIssue(itemId, code, message) {
    await this.db.prepare(`
      INSERT INTO plaid_transaction_cursors (item_id, cursor, last_synced_at, last_error_code, last_error_message)
      VALUES (?1, NULL, NULL, ?2, ?3)
      ON CONFLICT(item_id) DO UPDATE SET last_error_code = excluded.last_error_code, last_error_message = excluded.last_error_message
    `).bind(itemId, code || null, message || null).run();
  }

  async clearIssue(itemId) {
    await this.db.prepare('UPDATE plaid_transaction_cursors SET last_error_code = NULL, last_error_message = NULL WHERE item_id = ?1').bind(itemId).run();
  }

  async listTransactions(householdId, limit = 500) {
    const rows = await this.db.prepare(`
      SELECT transaction_id, account_id, name, merchant_name, amount, iso_date, pending,
             category_primary, category_detailed
      FROM plaid_transactions
      WHERE household_id = ?1
      ORDER BY iso_date DESC, transaction_id DESC
      LIMIT ?2
    `).bind(householdId, limit).all();
    return rows.results || [];
  }
}

function friendlyCategory(primary) {
  const value = String(primary || '').trim();
  if (!value) return 'Other';
  return value.toLowerCase().split('_').map((part) => part ? part[0].toUpperCase() + part.slice(1) : '').join(' ');
}

function publicTransaction(row) {
  const rawAmount = Number(row.amount || 0);
  const primary = row.category_primary || row.personal_finance_category?.primary || '';
  const transfer = String(primary).startsWith('TRANSFER_');
  return {
    id: `plaid:${row.transaction_id}`,
    source: 'PLAID',
    plaidTransactionId: row.transaction_id,
    accountKey: `plaid:${row.account_id}`,
    dateIso: row.iso_date || row.date,
    name: row.name || 'Transaction',
    merchantName: row.merchant_name || null,
    amount: Math.abs(rawAmount),
    type: transfer ? 'TRANSFER' : rawAmount < 0 ? 'INCOME' : 'EXPENSE',
    category: friendlyCategory(primary),
    notes: '',
    pending: Boolean(row.pending),
    excludedFromSpending: transfer
  };
}

export function createTransactionService(store, options = {}) {
  const post = options.plaidPost || plaidPost;
  const decrypt = options.decryptToken || ((packed, env) => decryptToken(env, packed));
  const now = options.now || (() => new Date());

  async function syncHousehold(context, env) {
    if (context?.mode !== 'session' || !context.householdId) throw Object.assign(new Error('Household session required'), { status: 401 });
    const items = await store.listItems(context.householdId);
    let addedCount = 0;
    let modifiedCount = 0;
    let removedCount = 0;
    const issues = [];

    for (const item of items) {
      try {
        const accessToken = await decrypt(item.accessTokenEnc, env);
        let cursor = await store.getCursor(item.itemId);
        let pages = 0;
        let hasMore = false;
        do {
          const body = { access_token: accessToken, count: PAGE_COUNT };
          if (cursor) body.cursor = cursor;
          const result = await post(env, '/transactions/sync', body);
          const added = result.added || [];
          const modified = result.modified || [];
          const removed = result.removed || [];
          cursor = result.next_cursor || cursor || null;
          await store.applyPage({
            householdId: context.householdId,
            itemId: item.itemId,
            nextCursor: cursor,
            added,
            modified,
            removed,
            nowIso: (now() instanceof Date ? now() : new Date(now())).toISOString()
          });
          addedCount += added.length;
          modifiedCount += modified.length;
          removedCount += removed.length;
          hasMore = Boolean(result.has_more);
          pages += 1;
        } while (hasMore && pages < MAX_PAGES_PER_ITEM);
        await store.clearIssue(item.itemId);
      } catch (error) {
        const code = error.plaid?.error_code || 'TRANSACTION_SYNC_FAILED';
        const message = error.message || code;
        await store.recordIssue(item.itemId, code, message);
        issues.push({
          itemId: item.itemId,
          label: item.label || null,
          errorCode: code,
          message,
          requiresReconnect: code === 'ITEM_LOGIN_REQUIRED'
        });
      }
    }
    return { added: addedCount, modified: modifiedCount, removed: removedCount, issues };
  }

  async function listHousehold(context, limit = 500) {
    if (context?.mode !== 'session' || !context.householdId) throw Object.assign(new Error('Household session required'), { status: 401 });
    return (await store.listTransactions(context.householdId, limit)).map(publicTransaction);
  }

  return { syncHousehold, listHousehold };
}

export async function handleTransactionsHttp(request, env, context, store = new D1TransactionStore(env.DB)) {
  const url = new URL(request.url);
  if (!url.pathname.startsWith('/api/transactions')) return null;
  const service = createTransactionService(store);
  if (request.method === 'POST' && url.pathname === '/api/transactions/sync') {
    return json(await service.syncHousehold(context, env));
  }
  if (request.method === 'GET' && url.pathname === '/api/transactions') {
    return json({ transactions: await service.listHousehold(context, 500) });
  }
  return json({ error: 'Not found' }, 404);
}

export async function syncAllHouseholdTransactions(env) {
  const rows = await env.DB.prepare('SELECT DISTINCT household_id FROM plaid_item_households').all();
  const store = new D1TransactionStore(env.DB);
  const service = createTransactionService(store);
  for (const row of rows.results || []) {
    await service.syncHousehold({ mode: 'session', householdId: row.household_id, userId: 'scheduled' }, env);
  }
}

export { publicTransaction };
