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
  async findAppliedSyncMutation(householdId, mutationId) {
    return this.mutations.get(this.mutationKey(householdId, mutationId)) || null;
  }
  async findFinanceRecord(householdId, kind, recordId) {
    return this.records.get(this.key(householdId, kind, recordId)) || null;
  }
  async applySyncMutation({ householdId, mutationId, appliedAt, record }) {
    this.records.set(this.key(householdId, record.kind, record.recordId), { ...record });
    this.events.push({
      eventId: this.nextEventId++, householdId, kind: record.kind, recordId: record.recordId,
      version: record.version, payloadJson: record.payloadJson, deleted: record.deleted, updatedAt: record.updatedAt
    });
    this.mutations.set(this.mutationKey(householdId, mutationId), { householdId, mutationId, appliedAt });
    return true;
  }
  async listSyncEvents(householdId, sinceEventId, limit) {
    return this.events.filter((e) => e.householdId === householdId && e.eventId > sinceEventId).slice(0, limit);
  }
}

const context = { mode: 'session', userId: 'owner-1', householdId: 'house-1', role: 'owner' };

function mutation(kind, recordId) {
  return {
    mutationId: `mutation-${kind}`,
    kind,
    recordId,
    baseVersion: 0,
    deleted: false,
    payload: { id: recordId }
  };
}

test('budget override and adjustment records are valid household sync kinds', async () => {
  const sync = createSyncService(new MemorySyncStore(), { now: () => new Date('2026-09-12T00:00:00Z') });
  const result = await sync.sync(context, {
    sinceEventId: 0,
    mutations: [
      mutation('budget_override', 'override-1'),
      mutation('budget_adjustment', 'adjustment-1')
    ]
  });

  assert.deepEqual(result.applied.map((row) => row.kind), ['budget_override', 'budget_adjustment']);
});
