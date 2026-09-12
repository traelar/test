import { httpError, json, readJson } from './http.js';
import { D1Store } from './store.js';

const ALLOWED_KINDS = new Set([
  'bill', 'payday', 'manual_account', 'settings', 'transaction',
  'budget', 'debt', 'savings_goal', 'reserved_fund', 'subscription_preference'
]);
const MAX_MUTATIONS = 100;
const MAX_PAYLOAD_BYTES = 64 * 1024;
const MAX_CHANGES = 500;
const encoder = new TextEncoder();

function requireString(value, field) {
  const text = String(value || '').trim();
  if (!text) throw httpError(400, `${field} is required`);
  if (text.length > 200) throw httpError(400, `${field} is too long`);
  return text;
}

function validateRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw httpError(400, 'Invalid sync request');
  const sinceEventId = body.sinceEventId ?? 0;
  if (!Number.isInteger(sinceEventId) || sinceEventId < 0) throw httpError(400, 'sinceEventId must be a non-negative integer');
  const mutations = body.mutations ?? [];
  if (!Array.isArray(mutations)) throw httpError(400, 'mutations must be an array');
  if (mutations.length > MAX_MUTATIONS) throw httpError(400, 'Too many mutations in one sync request');

  return {
    sinceEventId,
    mutations: mutations.map((mutation) => {
      if (!mutation || typeof mutation !== 'object' || Array.isArray(mutation)) throw httpError(400, 'Invalid mutation');
      const mutationId = requireString(mutation.mutationId, 'mutationId');
      const kind = requireString(mutation.kind, 'kind');
      if (!ALLOWED_KINDS.has(kind)) throw httpError(400, `Unsupported record kind: ${kind}`);
      const recordId = requireString(mutation.recordId, 'recordId');
      const baseVersion = mutation.baseVersion ?? 0;
      if (!Number.isInteger(baseVersion) || baseVersion < 0) throw httpError(400, 'baseVersion must be a non-negative integer');
      if (typeof mutation.deleted !== 'boolean') throw httpError(400, 'deleted must be a boolean');
      const payload = mutation.payload ?? {};
      if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) throw httpError(400, 'payload must be a JSON object');
      const payloadJson = JSON.stringify(payload);
      if (encoder.encode(payloadJson).byteLength > MAX_PAYLOAD_BYTES) throw httpError(400, 'Record payload exceeds 64 KiB');
      return { mutationId, kind, recordId, baseVersion, deleted: mutation.deleted, payload, payloadJson };
    })
  };
}

function parsePayload(payloadJson) {
  try {
    const value = JSON.parse(payloadJson || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}

function publicRecord(record) {
  return {
    kind: record.kind,
    recordId: record.recordId,
    version: Number(record.version),
    deleted: Boolean(record.deleted),
    payload: parsePayload(record.payloadJson),
    updatedAt: record.updatedAt
  };
}

function publicChange(event) {
  return {
    eventId: Number(event.eventId),
    kind: event.kind,
    recordId: event.recordId,
    version: Number(event.version),
    deleted: Boolean(event.deleted),
    payload: parsePayload(event.payloadJson),
    updatedAt: event.updatedAt
  };
}

function changedRows(result) {
  return Number(result?.meta?.changes ?? result?.changes ?? 0);
}

async function applyMutationWithVersionCheck(store, args, baseVersion) {
  if (!store.db?.prepare) {
    const result = await store.applySyncMutation(args);
    return result !== false;
  }

  const db = store.db;
  const record = args.record;
  let writeResult;
  if (baseVersion === 0) {
    writeResult = await db.prepare(`
      INSERT INTO finance_records (
        household_id, kind, record_id, payload_json, version,
        updated_at, updated_by_user_id, deleted
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
      ON CONFLICT(household_id, kind, record_id) DO NOTHING
    `).bind(
      record.householdId,
      record.kind,
      record.recordId,
      record.payloadJson,
      record.version,
      record.updatedAt,
      record.updatedByUserId,
      record.deleted
    ).run();
  } else {
    writeResult = await db.prepare(`
      UPDATE finance_records
      SET payload_json = ?4,
          version = ?5,
          updated_at = ?6,
          updated_by_user_id = ?7,
          deleted = ?8
      WHERE household_id = ?1
        AND kind = ?2
        AND record_id = ?3
        AND version = ?9
    `).bind(
      record.householdId,
      record.kind,
      record.recordId,
      record.payloadJson,
      record.version,
      record.updatedAt,
      record.updatedByUserId,
      record.deleted,
      baseVersion
    ).run();
  }

  if (changedRows(writeResult) !== 1) return false;

  await db.batch([
    db.prepare(`
      INSERT INTO sync_events (
        household_id, kind, record_id, version, payload_json, deleted, updated_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
    `).bind(
      record.householdId,
      record.kind,
      record.recordId,
      record.version,
      record.payloadJson,
      record.deleted,
      record.updatedAt
    ),
    db.prepare(`
      INSERT INTO sync_mutations (household_id, mutation_id, applied_at)
      VALUES (?1, ?2, ?3)
    `).bind(args.householdId, args.mutationId, args.appliedAt)
  ]);
  return true;
}

export function createSyncService(store, options = {}) {
  const nowProvider = options.now || (() => new Date());

  function nowIso() {
    const value = nowProvider();
    return (value instanceof Date ? value : new Date(value)).toISOString();
  }

  async function sync(context, rawBody) {
    if (!context?.householdId || !context?.userId) throw httpError(401, 'Unauthorized');
    const body = validateRequest(rawBody);
    const applied = [];
    const conflicts = [];

    for (const mutation of body.mutations) {
      const prior = await store.findAppliedSyncMutation(context.householdId, mutation.mutationId);
      if (prior) {
        const current = await store.findFinanceRecord(context.householdId, mutation.kind, mutation.recordId);
        applied.push({
          mutationId: mutation.mutationId,
          kind: mutation.kind,
          recordId: mutation.recordId,
          version: Number(current?.version || 0),
          replayed: true
        });
        continue;
      }

      const current = await store.findFinanceRecord(context.householdId, mutation.kind, mutation.recordId);
      const currentVersion = Number(current?.version || 0);
      if (currentVersion !== mutation.baseVersion) {
        conflicts.push({
          mutationId: mutation.mutationId,
          kind: mutation.kind,
          recordId: mutation.recordId,
          baseVersion: mutation.baseVersion,
          serverRecord: current ? publicRecord(current) : null
        });
        continue;
      }

      const updatedAt = nowIso();
      const record = {
        householdId: context.householdId,
        kind: mutation.kind,
        recordId: mutation.recordId,
        payloadJson: mutation.deleted ? '{}' : mutation.payloadJson,
        version: currentVersion + 1,
        updatedAt,
        updatedByUserId: context.userId,
        deleted: mutation.deleted ? 1 : 0
      };

      const writeSucceeded = await applyMutationWithVersionCheck(store, {
        householdId: context.householdId,
        mutationId: mutation.mutationId,
        appliedAt: updatedAt,
        record
      }, mutation.baseVersion);

      if (!writeSucceeded) {
        const latest = await store.findFinanceRecord(context.householdId, mutation.kind, mutation.recordId);
        conflicts.push({
          mutationId: mutation.mutationId,
          kind: mutation.kind,
          recordId: mutation.recordId,
          baseVersion: mutation.baseVersion,
          serverRecord: latest ? publicRecord(latest) : null
        });
        continue;
      }

      applied.push({
        mutationId: mutation.mutationId,
        kind: mutation.kind,
        recordId: mutation.recordId,
        version: record.version
      });
    }

    const events = await store.listSyncEvents(context.householdId, body.sinceEventId, MAX_CHANGES);
    const changes = events.map(publicChange);
    const cursor = changes.length ? changes.at(-1).eventId : body.sinceEventId;
    return { cursor, applied, conflicts, changes };
  }

  return { sync };
}

export async function handleSyncHttp(request, env, context, store = new D1Store(env.DB)) {
  const url = new URL(request.url);
  if (request.method !== 'POST' || url.pathname !== '/api/sync') return null;
  if (context.mode !== 'session') throw httpError(401, 'Household session required');
  const service = createSyncService(store);
  return json(await service.sync(context, await readJson(request)));
}

export { validateRequest, applyMutationWithVersionCheck };
