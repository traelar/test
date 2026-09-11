const fs = require('node:fs');
const path = require('node:path');

function createPlaidClientConfig(env = process.env) {
  const clientId = env.PLAID_CLIENT_ID;
  const secret = env.PLAID_SECRET;
  const environment = (env.PLAID_ENV || 'sandbox').toLowerCase();

  if (!clientId) throw new Error('PLAID_CLIENT_ID is required');
  if (!secret) throw new Error('PLAID_SECRET is required');
  if (!['sandbox', 'production'].includes(environment)) {
    throw new Error('PLAID_ENV must be sandbox or production');
  }

  return { clientId, secret, environment };
}

function plaidBaseUrl(environment) {
  return environment === 'production' ? 'https://production.plaid.com' : 'https://sandbox.plaid.com';
}

async function plaidPost(endpoint, body, env = process.env) {
  const config = createPlaidClientConfig(env);
  const response = await fetch(`${plaidBaseUrl(config.environment)}${endpoint}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      client_id: config.clientId,
      secret: config.secret,
      ...body
    })
  });

  const json = await response.json();
  if (!response.ok || json.error_code) {
    const message = json.error_message || json.error_code || `Plaid request failed (${response.status})`;
    const error = new Error(message);
    error.plaid = json;
    throw error;
  }
  return json;
}

function normalizeAccounts(accounts = [], itemId = null) {
  return accounts.map((account) => ({
    accountId: account.account_id,
    name: account.name,
    officialName: account.official_name || null,
    mask: account.mask || '',
    type: account.type || '',
    subtype: account.subtype || '',
    current: Number(account.balances?.current ?? 0),
    available: account.balances?.available == null ? null : Number(account.balances.available),
    ...(itemId ? { itemId } : {})
  }));
}

function createItemStore(filePath) {
  const resolved = path.resolve(filePath);
  return {
    load() {
      try {
        const parsed = JSON.parse(fs.readFileSync(resolved, 'utf8'));
        return Array.isArray(parsed.items) ? parsed.items : [];
      } catch {
        return [];
      }
    },
    save(items) {
      fs.mkdirSync(path.dirname(resolved), { recursive: true });
      fs.writeFileSync(resolved, JSON.stringify({ items }, null, 2));
    }
  };
}

module.exports = {
  createPlaidClientConfig,
  plaidBaseUrl,
  plaidPost,
  normalizeAccounts,
  createItemStore
};
