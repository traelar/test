import { authenticateSession, getBearerToken, handleAuthHttp } from './auth.js';
import { PASSWORD_KDF_ITERATIONS } from './crypto.js';
import { handleHouseholdHttp } from './households.js';
import { handlePlaidHttp, normalizeAccounts, encryptToken, decryptToken } from './plaid.js';
import { ensureSchema } from './schema.js';
import { handleSyncHttp } from './sync.js';
import { handleTransactionsHttp, syncAllHouseholdTransactions } from './transactions.js';

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'authorization, content-type',
      'access-control-allow-methods': 'GET,POST,DELETE,OPTIONS'
    }
  });
}

async function authorizeApiRequest(request, env) {
  const expected = String(env.BILLNEST_API_KEY || '');
  if (expected && getBearerToken(request) === expected) return { mode: 'legacy' };
  return { mode: 'session', ...(await authenticateSession(request, env)) };
}

async function route(request, env) {
  const url = new URL(request.url);
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: json({}).headers });
  if (request.method === 'GET' && url.pathname === '/health') {
    return json({
      ok: true,
      service: 'billnest-cloudflare',
      storage: 'd1',
      authKdfIterations: PASSWORD_KDF_ITERATIONS
    });
  }
  if (!url.pathname.startsWith('/api/')) return json({ error: 'Not found' }, 404);

  await ensureSchema(env);

  const authResponse = await handleAuthHttp(request, env);
  if (authResponse) return authResponse;

  const householdResponse = await handleHouseholdHttp(request, env);
  if (householdResponse) return householdResponse;

  const context = await authorizeApiRequest(request, env);

  const syncResponse = await handleSyncHttp(request, env, context);
  if (syncResponse) return syncResponse;

  const transactionResponse = await handleTransactionsHttp(request, env, context);
  if (transactionResponse) return transactionResponse;

  const plaidResponse = await handlePlaidHttp(request, env, context);
  if (plaidResponse) return plaidResponse;

  return json({ error: 'Not found' }, 404);
}

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      return json({
        error: error.message || 'Server error',
        plaidErrorCode: error.plaid?.error_code || null
      }, error.status || 500);
    }
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(syncAllHouseholdTransactions(env));
  }
};

export { normalizeAccounts, encryptToken, decryptToken };
