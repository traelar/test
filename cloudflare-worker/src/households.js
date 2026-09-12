import { randomToken, sha256Hex } from './crypto.js';
import { httpError, json } from './http.js';
import { D1Store } from './store.js';
import { authenticateSession } from './auth.js';

const INVITE_DAYS = 7;
const INVITE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function addDays(date, days) {
  return new Date(date.getTime() + days * 24 * 60 * 60 * 1000);
}

export function canonicalInviteCode(value) {
  return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

export function randomInviteCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(10));
  let buffer = 0;
  let bits = 0;
  let output = '';
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      output += INVITE_ALPHABET[(buffer >>> bits) & 31];
      buffer &= (1 << bits) - 1;
    }
  }
  if (bits > 0) output += INVITE_ALPHABET[(buffer << (5 - bits)) & 31];
  const sixteen = output.slice(0, 16).padEnd(16, INVITE_ALPHABET[0]);
  return sixteen.match(/.{1,4}/g).join('-');
}

export function createHouseholdService(store, options = {}) {
  const nowProvider = options.now || (() => new Date());
  const inviteCodeGenerator = options.inviteCodeGenerator || randomInviteCode;

  function now() {
    const value = nowProvider();
    return value instanceof Date ? value : new Date(value);
  }

  function requireOwner(context) {
    if (!context?.householdId || context.role !== 'owner') {
      throw httpError(403, 'Household owner access required');
    }
  }

  async function createInvite(context) {
    requireOwner(context);
    const rawCode = inviteCodeGenerator();
    const canonical = canonicalInviteCode(rawCode);
    if (canonical.length !== 16) throw new Error('Invite code generator must produce 16 characters');
    const createdAt = now();
    const invite = {
      inviteId: crypto.randomUUID(),
      householdId: context.householdId,
      codeHash: await sha256Hex(canonical),
      createdByUserId: context.userId,
      createdAt: createdAt.toISOString(),
      expiresAt: addDays(createdAt, INVITE_DAYS).toISOString(),
      redeemedAt: null
    };
    await store.createInvite(invite);
    return {
      inviteCode: rawCode,
      expiresAt: invite.expiresAt
    };
  }

  async function getHousehold(context) {
    if (!context?.householdId) throw httpError(401, 'Unauthorized');
    const household = await store.getHouseholdById(context.householdId);
    if (!household) throw httpError(404, 'Household not found');
    const members = await store.listHouseholdMembers(context.householdId);
    return {
      household: {
        householdId: household.householdId,
        name: household.name,
        ownerUserId: household.ownerUserId
      },
      members: members.map((member) => ({
        userId: member.userId,
        username: member.username,
        role: member.role,
        displayLabel: member.displayLabel,
        joinedAt: member.joinedAt
      }))
    };
  }

  async function removeMember(context, targetUserId) {
    requireOwner(context);
    const household = await store.getHouseholdById(context.householdId);
    if (!household) throw httpError(404, 'Household not found');
    if (targetUserId === household.ownerUserId) {
      throw httpError(400, 'The household owner cannot be removed');
    }
    const members = await store.listHouseholdMembers(context.householdId);
    if (!members.some((member) => member.userId === targetUserId)) {
      throw httpError(404, 'Household member not found');
    }
    await store.removeHouseholdMember(context.householdId, targetUserId);
    await store.deleteSessionsForUser(targetUserId);
    return { ok: true };
  }

  return { createInvite, getHousehold, removeMember };
}

export async function handleHouseholdHttp(request, env, store = new D1Store(env.DB), context = null) {
  const url = new URL(request.url);
  const isHouseholdRoute = url.pathname === '/api/household' ||
    url.pathname === '/api/household/invites' ||
    url.pathname.startsWith('/api/household/members/');
  if (!isHouseholdRoute) return null;

  try {
    const sessionContext = context || await authenticateSession(request, env);
    const service = createHouseholdService(store, {
      clientIp: request.headers.get('CF-Connecting-IP') || 'unknown'
    });

    if (request.method === 'GET' && url.pathname === '/api/household') {
      return json(await service.getHousehold(sessionContext));
    }
    if (request.method === 'POST' && url.pathname === '/api/household/invites') {
      return json(await service.createInvite(sessionContext));
    }
    if (request.method === 'DELETE' && url.pathname.startsWith('/api/household/members/')) {
      const userId = decodeURIComponent(url.pathname.slice('/api/household/members/'.length));
      return json(await service.removeMember(sessionContext, userId));
    }
    return json({ error: 'Not found' }, 404);
  } catch (error) {
    return json({ error: error.message || 'Server error' }, error.status || 500);
  }
}

export { INVITE_DAYS };
