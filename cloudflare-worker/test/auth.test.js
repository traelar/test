import test from 'node:test';
import assert from 'node:assert/strict';
import { hashPassword, verifyPassword, sha256Hex } from '../src/crypto.js';
import { createAuthService } from '../src/auth.js';

class MemoryStore {
  constructor() {
    this.users = [];
    this.households = [];
    this.members = [];
    this.sessions = [];
    this.rateLimits = new Map();
    this.claimedPlaidItems = [];
  }

  async countUsers() { return this.users.length; }
  async findUserByNormalizedUsername(usernameNorm) {
    return this.users.find((user) => user.usernameNorm === usernameNorm) || null;
  }
  async createUser(user) { this.users.push({ ...user }); }
  async createHousehold(household) { this.households.push({ ...household }); }
  async addHouseholdMember(member) { this.members.push({ ...member }); }
  async getMembershipForUser(userId) {
    const member = this.members.find((item) => item.userId === userId);
    if (!member) return null;
    const household = this.households.find((item) => item.householdId === member.householdId);
    return {
      ...member,
      householdName: household?.name || ''
    };
  }
  async createSession(session) { this.sessions.push({ ...session }); }
  async findSessionByTokenHash(tokenHash) {
    const session = this.sessions.find((item) => item.tokenHash === tokenHash);
    if (!session) return null;
    const user = this.users.find((item) => item.userId === session.userId);
    const membership = await this.getMembershipForUser(session.userId);
    return {
      ...session,
      username: user?.username || '',
      householdId: membership?.householdId || null,
      householdName: membership?.householdName || '',
      role: membership?.role || null,
      displayLabel: membership?.displayLabel || ''
    };
  }
  async deleteSessionByTokenHash(tokenHash) {
    this.sessions = this.sessions.filter((item) => item.tokenHash !== tokenHash);
  }
  async claimUnmappedPlaidItems(householdId, userId, createdAt) {
    this.claimedPlaidItems.push({ householdId, userId, createdAt });
  }
  async readRateLimit(bucket) { return this.rateLimits.get(bucket) || null; }
  async writeRateLimit(bucket, windowStartedMs, attemptCount) {
    this.rateLimits.set(bucket, { bucket, windowStartedMs, attemptCount });
  }
}

function serviceWith(store = new MemoryStore()) {
  return {
    store,
    service: createAuthService(store, {
      now: () => new Date('2026-09-11T23:00:00.000Z'),
      clientIp: '127.0.0.1'
    })
  };
}

test('password hash verifies the right password and rejects a wrong password', async () => {
  const stored = await hashPassword('Correct Horse Battery Staple');
  assert.equal(await verifyPassword('Correct Horse Battery Staple', stored), true);
  assert.equal(await verifyPassword('wrong password', stored), false);
});

test('bootstrap creates exactly one owner household and cannot run twice', async () => {
  const { service, store } = serviceWith();
  const first = await service.bootstrap({
    username: 'baylee',
    password: 'a real password',
    householdName: 'Our Household'
  });

  assert.equal(first.household.role, 'owner');
  assert.equal(first.household.name, 'Our Household');
  assert.equal(store.claimedPlaidItems.length, 1);

  await assert.rejects(
    () => service.bootstrap({
      username: 'other',
      password: 'another password',
      householdName: 'Other'
    }),
    (error) => error.status === 409
  );
});

test('login stores only a hash of the returned session token', async () => {
  const { service, store } = serviceWith();
  await service.bootstrap({
    username: 'baylee',
    password: 'a real password',
    householdName: 'Our Household'
  });
  store.sessions = [];

  const result = await service.login({
    username: 'BAYLEE',
    password: 'a real password'
  });

  assert.equal(store.sessions.length, 1);
  assert.notEqual(store.sessions[0].tokenHash, result.sessionToken);
  assert.equal(store.sessions[0].tokenHash, await sha256Hex(result.sessionToken));
});

test('login rejects a wrong password without returning a session', async () => {
  const { service, store } = serviceWith();
  await service.bootstrap({
    username: 'baylee',
    password: 'a real password',
    householdName: 'Our Household'
  });
  store.sessions = [];

  await assert.rejects(
    () => service.login({ username: 'baylee', password: 'wrong password' }),
    (error) => error.status === 401
  );
  assert.equal(store.sessions.length, 0);
});

test('ninth failed login attempt in one rate-limit window is rejected with 429', async () => {
  const { service } = serviceWith();
  await service.bootstrap({
    username: 'baylee',
    password: 'a real password',
    householdName: 'Our Household'
  });

  for (let attempt = 0; attempt < 8; attempt += 1) {
    await assert.rejects(
      () => service.login({ username: 'baylee', password: 'wrong password' }),
      (error) => error.status === 401
    );
  }

  await assert.rejects(
    () => service.login({ username: 'baylee', password: 'wrong password' }),
    (error) => error.status === 429
  );
});
