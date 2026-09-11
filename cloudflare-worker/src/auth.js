import { hashPassword, randomToken, sha256Hex, verifyPassword } from './crypto.js';
import { httpError } from './http.js';
import { D1Store } from './store.js';

const RATE_WINDOW_MS = 15 * 60 * 1000;
const MAX_ATTEMPTS = 8;
const SESSION_DAYS = 30;

function normalizeUsername(value) {
  const username = String(value || '').trim();
  if (username.length < 3 || username.length > 40) {
    throw httpError(400, 'Username must be 3 to 40 characters');
  }
  if (!/^[A-Za-z0-9._-]+$/.test(username)) {
    throw httpError(400, 'Username may contain letters, numbers, dot, dash, and underscore');
  }
  return { username, usernameNorm: username.toLowerCase() };
}

function normalizeHouseholdName(value) {
  const name = String(value || '').trim();
  if (name.length < 1 || name.length > 80) {
    throw httpError(400, 'Household name must be 1 to 80 characters');
  }
  return name;
}

function asDate(value) {
  return value instanceof Date ? value : new Date(value);
}

function addDays(date, days) {
  return new Date(date.getTime() + days * 24 * 60 * 60 * 1000);
}

export function getBearerToken(request) {
  const header = request.headers.get('authorization') || '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match?.[1]?.trim() || '';
}

export function createAuthService(store, options = {}) {
  const nowProvider = options.now || (() => new Date());
  const clientIp = options.clientIp || 'unknown';

  function now() {
    return asDate(nowProvider());
  }

  async function rateState(kind, usernameNorm) {
    const bucketHash = await sha256Hex(`${kind}|${usernameNorm}|${clientIp}`);
    const bucket = `auth:${kind}:${bucketHash}`;
    const timestamp = now().getTime();
    let state = await store.readRateLimit(bucket);
    if (!state || timestamp - state.windowStartedMs >= RATE_WINDOW_MS) {
      state = { bucket, windowStartedMs: timestamp, attemptCount: 0 };
      await store.writeRateLimit(bucket, state.windowStartedMs, state.attemptCount);
    }
    if (state.attemptCount >= MAX_ATTEMPTS) {
      throw httpError(429, 'Too many sign-in attempts. Try again later.');
    }
    return state;
  }

  async function recordFailure(state) {
    await store.writeRateLimit(state.bucket, state.windowStartedMs, state.attemptCount + 1);
  }

  async function resetRateLimit(state) {
    await store.writeRateLimit(state.bucket, now().getTime(), 0);
  }

  async function issueSession(user, membership) {
    if (!membership) throw httpError(403, 'User is not assigned to a household');
    const rawToken = randomToken(32);
    const createdAt = now();
    const session = {
      sessionId: crypto.randomUUID(),
      userId: user.userId,
      tokenHash: await sha256Hex(rawToken),
      createdAt: createdAt.toISOString(),
      expiresAt: addDays(createdAt, SESSION_DAYS).toISOString(),
      lastSeenAt: createdAt.toISOString()
    };
    await store.createSession(session);
    return {
      sessionToken: rawToken,
      user: {
        userId: user.userId,
        username: user.username
      },
      household: {
        householdId: membership.householdId,
        name: membership.householdName || '',
        role: membership.role,
        displayLabel: membership.displayLabel
      }
    };
  }

  async function bootstrap({ username: rawUsername, password, householdName: rawHouseholdName }) {
    if (await store.countUsers() > 0) {
      throw httpError(409, 'BillNest household has already been initialized');
    }

    const { username, usernameNorm } = normalizeUsername(rawUsername);
    const householdName = normalizeHouseholdName(rawHouseholdName);
    const limit = await rateState('bootstrap', usernameNorm);
    const passwordData = await hashPassword(password);
    const createdAt = now().toISOString();
    const user = {
      userId: crypto.randomUUID(),
      username,
      usernameNorm,
      passwordSalt: passwordData.salt,
      passwordHash: passwordData.hash,
      passwordIterations: passwordData.iterations,
      createdAt
    };
    const household = {
      householdId: crypto.randomUUID(),
      name: householdName,
      ownerUserId: user.userId,
      createdAt
    };
    const membership = {
      householdId: household.householdId,
      userId: user.userId,
      role: 'owner',
      displayLabel: username,
      joinedAt: createdAt,
      householdName
    };

    await store.createUser(user);
    await store.createHousehold(household);
    await store.addHouseholdMember(membership);
    await store.claimUnmappedPlaidItems(household.householdId, user.userId, createdAt);
    const result = await issueSession(user, membership);
    await resetRateLimit(limit);
    return result;
  }

  async function login({ username: rawUsername, password }) {
    const { usernameNorm } = normalizeUsername(rawUsername);
    const limit = await rateState('login', usernameNorm);
    const user = await store.findUserByNormalizedUsername(usernameNorm);
    const valid = user && await verifyPassword(password, {
      salt: user.passwordSalt,
      hash: user.passwordHash,
      iterations: user.passwordIterations
    });
    if (!valid) {
      await recordFailure(limit);
      throw httpError(401, 'Invalid username or password');
    }

    const membership = await store.getMembershipForUser(user.userId);
    if (!membership) throw httpError(403, 'User is not assigned to a household');
    await resetRateLimit(limit);
    return issueSession(user, membership);
  }

  async function logout(sessionToken) {
    if (!sessionToken) return;
    await store.deleteSessionByTokenHash(await sha256Hex(sessionToken));
  }

  async function contextForSession(sessionToken) {
    if (!sessionToken) throw httpError(401, 'Unauthorized');
    const tokenHash = await sha256Hex(sessionToken);
    const session = await store.findSessionByTokenHash(tokenHash);
    if (!session) throw httpError(401, 'Unauthorized');
    if (new Date(session.expiresAt).getTime() <= now().getTime()) {
      await store.deleteSessionByTokenHash(tokenHash);
      throw httpError(401, 'Session expired');
    }
    if (!session.householdId || !session.role) {
      throw httpError(403, 'User is not assigned to a household');
    }
    return {
      userId: session.userId,
      householdId: session.householdId,
      householdName: session.householdName || '',
      role: session.role,
      username: session.username,
      displayLabel: session.displayLabel,
      sessionTokenHash: tokenHash
    };
  }

  return { bootstrap, login, logout, contextForSession };
}

export async function authenticateSession(request, env) {
  const service = createAuthService(new D1Store(env.DB), {
    clientIp: request.headers.get('CF-Connecting-IP') || 'unknown'
  });
  return service.contextForSession(getBearerToken(request));
}

export { normalizeUsername };
