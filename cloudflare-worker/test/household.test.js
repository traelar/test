import test from 'node:test';
import assert from 'node:assert/strict';
import { createAuthService } from '../src/auth.js';
import { createHouseholdService } from '../src/households.js';

class MemoryStore {
  constructor() {
    this.users = [];
    this.households = [];
    this.members = [];
    this.sessions = [];
    this.invites = [];
    this.rateLimits = new Map();
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
    return { ...member, householdName: household?.name || '' };
  }
  async getHouseholdById(householdId) {
    return this.households.find((item) => item.householdId === householdId) || null;
  }
  async listHouseholdMembers(householdId) {
    return this.members.filter((item) => item.householdId === householdId).map((member) => {
      const user = this.users.find((item) => item.userId === member.userId);
      return { ...member, username: user?.username || '' };
    });
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
  async deleteSessionsForUser(userId) {
    this.sessions = this.sessions.filter((item) => item.userId !== userId);
  }
  async claimUnmappedPlaidItems() {}
  async readRateLimit(bucket) { return this.rateLimits.get(bucket) || null; }
  async writeRateLimit(bucket, windowStartedMs, attemptCount) {
    this.rateLimits.set(bucket, { bucket, windowStartedMs, attemptCount });
  }

  async createInvite(invite) { this.invites.push({ ...invite }); }
  async findUsableInviteByCodeHash(codeHash, nowIso) {
    return this.invites.find((invite) =>
      invite.codeHash === codeHash &&
      invite.redeemedAt == null &&
      invite.expiresAt > nowIso
    ) || null;
  }
  async redeemInvite(inviteId, redeemedAt) {
    const invite = this.invites.find((item) => item.inviteId === inviteId);
    if (!invite || invite.redeemedAt) return false;
    invite.redeemedAt = redeemedAt;
    return true;
  }
  async createInvitedMemberBundle({ user, member, session, inviteId, redeemedAt }) {
    const invite = this.invites.find((item) => item.inviteId === inviteId);
    if (!invite || invite.redeemedAt) throw Object.assign(new Error('Invite already used'), { status: 409 });
    this.users.push({ ...user });
    this.members.push({ ...member });
    this.sessions.push({ ...session });
    invite.redeemedAt = redeemedAt;
  }
  async removeHouseholdMember(householdId, userId) {
    this.members = this.members.filter((item) => !(item.householdId === householdId && item.userId === userId));
  }
}

const now = () => new Date('2026-09-11T23:00:00.000Z');

async function setupHousehold() {
  const store = new MemoryStore();
  const auth = createAuthService(store, { now, clientIp: '127.0.0.1' });
  const owner = await auth.bootstrap({
    username: 'baylee',
    password: 'a real password',
    householdName: 'Our Household'
  });
  const ownerContext = await auth.contextForSession(owner.sessionToken);
  const households = createHouseholdService(store, {
    now,
    inviteCodeGenerator: () => 'ABCD-EFGH-IJKL-MNOP'
  });
  return { store, auth, households, owner, ownerContext };
}

test('owner can create a one-time invite and invited user joins the same household', async () => {
  const { auth, households, ownerContext } = await setupHousehold();
  const invite = await households.createInvite(ownerContext);

  assert.equal(invite.inviteCode, 'ABCD-EFGH-IJKL-MNOP');
  const joined = await auth.registerWithInvite({
    inviteCode: invite.inviteCode,
    username: 'fiance',
    password: 'another real password'
  });

  assert.equal(joined.household.householdId, ownerContext.householdId);
  assert.equal(joined.household.role, 'member');
  assert.equal(joined.user.username, 'fiance');
});

test('redeemed invite cannot be reused', async () => {
  const { auth, households, ownerContext } = await setupHousehold();
  const invite = await households.createInvite(ownerContext);

  await auth.registerWithInvite({
    inviteCode: invite.inviteCode,
    username: 'one',
    password: '1234567890x'
  });

  await assert.rejects(
    () => auth.registerWithInvite({
      inviteCode: invite.inviteCode,
      username: 'two',
      password: '1234567890y'
    }),
    (error) => error.status === 409
  );
});

test('expired invite cannot be used', async () => {
  const { store, auth, households, ownerContext } = await setupHousehold();
  const invite = await households.createInvite(ownerContext);
  store.invites[0].expiresAt = '2026-09-10T23:00:00.000Z';

  await assert.rejects(
    () => auth.registerWithInvite({
      inviteCode: invite.inviteCode,
      username: 'fiance',
      password: 'another real password'
    }),
    (error) => error.status === 404
  );
});

test('member cannot create invites or remove another member', async () => {
  const { auth, households, ownerContext } = await setupHousehold();
  const invite = await households.createInvite(ownerContext);
  const joined = await auth.registerWithInvite({
    inviteCode: invite.inviteCode,
    username: 'fiance',
    password: 'another real password'
  });
  const memberContext = await auth.contextForSession(joined.sessionToken);

  await assert.rejects(() => households.createInvite(memberContext), (error) => error.status === 403);
  await assert.rejects(() => households.removeMember(memberContext, ownerContext.userId), (error) => error.status === 403);
});

test('owner can remove a member and all of that member sessions are revoked', async () => {
  const { auth, households, ownerContext } = await setupHousehold();
  const invite = await households.createInvite(ownerContext);
  const joined = await auth.registerWithInvite({
    inviteCode: invite.inviteCode,
    username: 'fiance',
    password: 'another real password'
  });

  await households.removeMember(ownerContext, joined.user.userId);

  await assert.rejects(
    () => auth.contextForSession(joined.sessionToken),
    (error) => error.status === 401
  );
});

test('owner cannot remove the owner account', async () => {
  const { households, ownerContext } = await setupHousehold();
  await assert.rejects(
    () => households.removeMember(ownerContext, ownerContext.userId),
    (error) => error.status === 400
  );
});
