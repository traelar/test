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

  key(householdId, kind, recordId) {
    return `${householdId}|${kind}|${recordId}`;
  }

  mutationKey(householdId, mutationId) {
    return `${householdId}|${mutationId}`;
  }

  async findAppliedSyncMutation(householdId, mutationId) {
    return this.mutations.get(this.mutationKey(householdId, mutationId)) || null;
  }

  async findFinanceRecord(householdId, kind, recordId) {
    return this.records.get(this.key(householdId, kind, recordId)) || null;
  }

  async applySyncMutation({ householdId, mutationId, appliedAt, record }) {
    this.records.set(this.key(householdId, record.kind, record.recordId), { ...record });
    const event = {
      eventId: this.nextEventId++,
      householdId,
      kind: record.kind,
      recordId: record.recordId,
      version: record.version,
      payloadJson: record.payloadJson,
      deleted: record.deleted,
      updatedAt: record.updatedAt
    };
    this.events.push(event);
    this.mutations.set(this.mutationKey(householdId, mutationId), {
      householdId,
      mutationId,
      appliedAt
    });
    return event;
  }

  async listSyncEvents(householdId, sinceEventId, limit) {
    return this.events
      .filter((event) => event.householdId === householdId && event.eventId > sinceEventId)
      .sort((a, b) => a.eventId - b.eventId)
      .slice(0, limit);
  }

  countRecords(kind, householdId = 'house-1') {
    return [...this.records.values()].filter((record) => record.kind === kind && record.householdId === householdId).length;
  }
}

const ownerContext = {
  mode: 'session',
  userId: 'owner-1',
  householdId: 'house-1',
  role: 'owner'
};

const otherDeviceContext = {
  mode: 'session',
  userId: 'member-1',
  householdId: 'house-1',
  role: 'member'
};

const otherHouseholdContext = {
  mode: 'session',
  userId: 'owner-2',
  householdId: 'house-2',
  role: 'owner'
};

function service(store = new MemorySyncStore()) {
  return {
    store,
    sync: createSyncService(store, {
      now: () => new Date('2026-09-12T00:15:00.000Z')
    })
  };
}

function mutation(overrides = {}) {
  return {
    mutationId: 'mutation-1',
    kind: 'bill',
    recordId: 'bill-1',
    baseVersion: 0,
    deleted: false,
    payload: { id: 'bill-1', name: 'Electric', amount: 121.44 },
    ...overrides
  };
}

test('replaying the same mutation id is idempotent', async () => {
  const { sync, store } = service();
  const request = { sinceEventId: 0, mutations: [mutation()] };

  const first = await sync.sync(ownerContext, request);
  const second = await sync.sync(ownerContext, request);

  assert.equal(first.applied[0].version, 1);
  assert.equal(second.applied[0].version, 1);
  assert.equal(store.countRecords('bill'), 1);
  assert.equal(store.events.length, 1);
});

test('stale baseVersion returns a conflict instead of overwriting server state', async () => {
  const { sync } = service();

  await sync.sync(ownerContext, { sinceEventId: 0, mutations: [mutation()] });
  await sync.sync(ownerContext, {
    sinceEventId: 0,
    mutations: [mutation({
      mutationId: 'mutation-2',
      baseVersion: 1,
      payload: { id: 'bill-1', name: 'Electric', amount: 140 }
    })]
  });

  const result = await sync.sync(otherDeviceContext, {
    sinceEventId: 0,
    mutations: [mutation({
      mutationId: 'mutation-3',
      baseVersion: 1,
      payload: { id: 'bill-1', name: 'Electric', amount: 130 }
    })]
  });

  assert.equal(result.applied.length, 0);
  assert.equal(result.conflicts.length, 1);
  assert.equal(result.conflicts[0].serverRecord.version, 2);
  assert.equal(result.conflicts[0].serverRecord.payload.amount, 140);
});

test('write that loses the optimistic-version race becomes a conflict', async () => {
  class RaceStore extends MemorySyncStore {
    async applySyncMutation({ householdId, record }) {
      this.records.set(this.key(householdId, record.kind, record.recordId), {
        ...record,
        payloadJson: JSON.stringify({ id: record.recordId, name: 'Winner from other phone', amount: 150 }),
        version: record.version,
        updatedByUserId: 'other-phone'
      });
      return false;
    }
  }

  const store = new RaceStore();
  const { sync } = service(store);
  const result = await sync.sync(ownerContext, { sinceEventId: 0, mutations: [mutation()] });

  assert.equal(result.applied.length, 0);
  assert.equal(result.conflicts.length, 1);
  assert.equal(result.conflicts[0].serverRecord.payload.amount, 150);
});

test('delete produces a tombstone event', async () => {
  const { sync } = service();
  await sync.sync(ownerContext, { sinceEventId: 0, mutations: [mutation()] });

  const result = await sync.sync(ownerContext, {
    sinceEventId: 0,
    mutations: [mutation({
      mutationId: 'mutation-delete',
      baseVersion: 1,
      deleted: true,
      payload: {}
    })]
  });

  assert.equal(result.changes.at(-1).deleted, true);
  assert.equal(result.changes.at(-1).version, 2);
});

test('pull returns only events for the authenticated household', async () => {
  const { sync } = service();
  await sync.sync(ownerContext, { sinceEventId: 0, mutations: [mutation()] });
  await sync.sync(otherHouseholdContext, {
    sinceEventId: 0,
    mutations: [mutation({ mutationId: 'other-house-mutation', recordId: 'bill-2', payload: { id: 'bill-2', name: 'Other' } })]
  });

  const result = await sync.sync(ownerContext, { sinceEventId: 0, mutations: [] });
  assert.deepEqual(result.changes.map((change) => change.recordId), ['bill-1']);
});

test('unsupported record kind is rejected', async () => {
  const { sync } = service();
  await assert.rejects(
    () => sync.sync(ownerContext, { sinceEventId: 0, mutations: [mutation({ kind: 'secret' })] }),
    (error) => error.status === 400
  );
});

test('mutation batches larger than 100 are rejected', async () => {
  const { sync } = service();
  const mutations = Array.from({ length: 101 }, (_, index) => mutation({
    mutationId: `mutation-${index}`,
    recordId: `bill-${index}`,
    payload: { id: `bill-${index}` }
  }));

  await assert.rejects(
    () => sync.sync(ownerContext, { sinceEventId: 0, mutations }),
    (error) => error.status === 400
  );
});

test('record payloads larger than 64 KiB are rejected', async () => {
  const { sync } = service();
  await assert.rejects(
    () => sync.sync(ownerContext, {
      sinceEventId: 0,
      mutations: [mutation({ payload: { id: 'bill-1', note: 'x'.repeat(70 * 1024) } })]
    }),
    (error) => error.status === 400
  );
});
