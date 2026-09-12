import { httpError, json, readJson } from './http.js';
import { D1Store } from './store.js';

const ALLOWED_KINDS = new Set(['bill', 'payday', 'manual_account', 'settings']);
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

      await store.applySyncMutation({
        householdId: context.householdId,
        mutationId: mutation.mutationId,
        appliedAt: updatedAt,
        record
      });

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

export { validateRequest };
