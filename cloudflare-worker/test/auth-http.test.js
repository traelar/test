import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAuthHttp } from '../src/auth.js';

class MemoryStore {
  constructor() {
    this.users = [];
    this.households = [];
    this.members = [];
    this.sessions = [];
    this.rateLimits = new Map();
  }
  async countUsers() { return this.users.length; }
  async findUserByNormalizedUsername(usernameNorm) { return this.users.find((u) => u.usernameNorm === usernameNorm) || null; }
  async createUser(user) { this.users.push({ ...user }); }
  async createHousehold(household) { this.households.push({ ...household }); }
  async addHouseholdMember(member) { this.members.push({ ...member }); }
  async getMembershipForUser(userId) {
    const member = this.members.find((m) => m.userId === userId);
    if (!member) return null;
    const household = this.households.find((h) => h.householdId === member.householdId);
    return { ...member, householdName: household?.name || '' };
  }
  async createSession(session) { this.sessions.push({ ...session }); }
  async findSessionByTokenHash(tokenHash) {
    const session = this.sessions.find((s) => s.tokenHash === tokenHash);
    if (!session) return null;
    const user = this.users.find((u) => u.userId === session.userId);
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
  async deleteSessionByTokenHash(tokenHash) { this.sessions = this.sessions.filter((s) => s.tokenHash !== tokenHash); }
  async claimUnmappedPlaidItems() {}
  async readRateLimit(bucket) { return this.rateLimits.get(bucket) || null; }
  async writeRateLimit(bucket, windowStartedMs, attemptCount) { this.rateLimits.set(bucket, { bucket, windowStartedMs, attemptCount }); }
}

function request(path, { method = 'GET', token = null, json = null } = {}) {
  const headers = new Headers();
  if (token) headers.set('authorization', `Bearer ${token}`);
  if (json) headers.set('content-type', 'application/json');
  return new Request(`https://billnest.test${path}`, {
    method,
    headers,
    body: json ? JSON.stringify(json) : undefined
  });
}

const env = { BILLNEST_API_KEY: 'legacy-key' };
const options = { now: () => new Date('2026-09-11T23:00:00.000Z'), clientIp: '127.0.0.1' };

test('bootstrap requires the legacy backend key and returns the first owner session', async () => {
  const store = new MemoryStore();
  const body = { username: 'baylee', password: 'a real password', householdName: 'Our Household' };

  const unauthorized = await handleAuthHttp(request('/api/auth/bootstrap', { method: 'POST', json: body }), env, store, options);
  assert.equal(unauthorized.status, 401);

  const response = await handleAuthHttp(request('/api/auth/bootstrap', {
    method: 'POST',
    token: 'legacy-key',
    json: body
  }), env, store, options);
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.user.username, 'baylee');
  assert.equal(payload.household.role, 'owner');
  assert.equal(typeof payload.sessionToken, 'string');
  assert.equal(payload.sessionToken.length > 20, true);
});

test('login does not require the legacy backend key and session can read /api/me', async () => {
  const store = new MemoryStore();
  const bootstrap = await handleAuthHttp(request('/api/auth/bootstrap', {
    method: 'POST',
    token: 'legacy-key',
    json: { username: 'baylee', password: 'a real password', householdName: 'Our Household' }
  }), env, store, options);
  assert.equal(bootstrap.status, 200);

  const login = await handleAuthHttp(request('/api/auth/login', {
    method: 'POST',
    json: { username: 'BAYLEE', password: 'a real password' }
  }), env, store, options);
  assert.equal(login.status, 200);
  const loginPayload = await login.json();

  const me = await handleAuthHttp(request('/api/me', { token: loginPayload.sessionToken }), env, store, options);
  assert.equal(me.status, 200);
  assert.deepEqual(await me.json(), {
    user: { userId: loginPayload.user.userId, username: 'baylee' },
    household: {
      householdId: loginPayload.household.householdId,
      name: 'Our Household',
      role: 'owner',
      displayLabel: 'baylee'
    }
  });
});

test('logout revokes the session', async () => {
  const store = new MemoryStore();
  const bootstrap = await handleAuthHttp(request('/api/auth/bootstrap', {
    method: 'POST',
    token: 'legacy-key',
    json: { username: 'baylee', password: 'a real password', householdName: 'Our Household' }
  }), env, store, options);
  const token = (await bootstrap.json()).sessionToken;

  const logout = await handleAuthHttp(request('/api/auth/logout', { method: 'POST', token }), env, store, options);
  assert.equal(logout.status, 200);

  const me = await handleAuthHttp(request('/api/me', { token }), env, store, options);
  assert.equal(me.status, 401);
});
