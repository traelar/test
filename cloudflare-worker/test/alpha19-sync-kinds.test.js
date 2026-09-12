import test from 'node:test';
import assert from 'node:assert/strict';
import { createSyncService } from '../src/sync.js';

class MemorySyncStore {
  constructor() {
    this.records = new Map();
    this.events = [];
    this.mutations = new Map();
    this.nextEventId = 1;
  }
  key(householdId, kind, recordId) { return `${householdId}|${kind}|${recordId}`; }
  mutationKey(householdId, mutationId) { return `${householdId}|${mutationId}`; }
  async findAppliedSyncMutation(householdId, mutationId) { return this.mutations.get(this.mutationKey(householdId, mutationId)) || null; }
  async findFinanceRecord(householdId, kind, recordId) { return this.records.get(this.key(householdId, kind, recordId)) || null; }
  async applySyncMutation({ householdId, mutationId, appliedAt, record }) {
    this.records.set(this.key(householdId, record.kind, record.recordId), { ...record });
    this.events.push({ eventId: this.nextEventId++, householdId, kind: record.kind, recordId: record.recordId, version: record.version, payloadJson: record.payloadJson, deleted: record.deleted, updatedAt: record.updatedAt });
    this.mutations.set(this.mutationKey(householdId, mutationId), { householdId, mutationId, appliedAt });
    return true;
  }
  async listSyncEvents(householdId, sinceEventId, limit) {
    return this.events.filter((event) => event.householdId === householdId && event.eventId > sinceEventId).slice(0, limit);
  }
}

const context = { mode: 'session', userId: 'owner-1', householdId: 'house-1', role: 'owner' };

function mutation(kind) {
  return {
    mutationId: `alpha19-${kind}`,
    kind,
    recordId: `${kind}-1`,
    baseVersion: 0,
    deleted: false,
    payload: { id: `${kind}-1` }
  };
}

test('transaction rules tombstones and financial snapshots are household sync kinds', async () => {
  const sync = createSyncService(new MemorySyncStore(), { now: () => new Date('2026-09-12T00:00:00Z') });
  const kinds = ['transaction_rule', 'transaction_tombstone', 'financial_snapshot'];
  const result = await sync.sync(context, { sinceEventId: 0, mutations: kinds.map(mutation) });
  assert.deepEqual(result.applied.map((row) => row.kind), kinds);
});
