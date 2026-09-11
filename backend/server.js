const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');
const {
  plaidPost,
  normalizeAccounts,
  createItemStore
} = require('./src/plaid');

function loadEnvFile(filePath) {
  try {
    const text = fs.readFileSync(filePath, 'utf8');
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#')) continue;
      const index = line.indexOf('=');
      if (index <= 0) continue;
      const key = line.slice(0, index).trim();
      let value = line.slice(index + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      if (!process.env[key]) process.env[key] = value;
    }
  } catch {}
}

loadEnvFile(path.join(__dirname, '.env'));

const port = Number(process.env.PORT || 8787);
const itemsFile = process.env.PLAID_ITEMS_FILE || path.join(__dirname, 'data', 'plaid-items.json');
const store = createItemStore(itemsFile);

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type'
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'access-control-allow-headers': 'content-type'
    });
    return res.end();
  }

  try {
    if (req.method === 'GET' && req.url === '/health') {
      return json(res, 200, { ok: true, service: 'billnest-plaid' });
    }

    if (req.method === 'POST' && req.url === '/api/plaid/link-token') {
      const result = await plaidPost('/link/token/create', {
        client_name: 'BillNest',
        language: 'en',
        country_codes: ['US'],
        products: ['transactions'],
        user: { client_user_id: process.env.PLAID_USER_ID || 'billnest-personal' }
      });
      return json(res, 200, { linkToken: result.link_token, expiration: result.expiration });
    }

    if (req.method === 'POST' && req.url === '/api/plaid/exchange') {
      const body = await readJson(req);
      if (!body.publicToken) return json(res, 400, { error: 'publicToken is required' });

      const result = await plaidPost('/item/public_token/exchange', {
        public_token: body.publicToken
      });

      const items = store.load().filter((item) => item.itemId !== result.item_id);
      items.push({
        itemId: result.item_id,
        accessToken: result.access_token,
        label: body.label || null,
        createdAt: new Date().toISOString()
      });
      store.save(items);

      return json(res, 200, { itemId: result.item_id });
    }

    if (req.method === 'GET' && req.url === '/api/plaid/accounts') {
      const items = store.load();
      const accounts = [];

      for (const item of items) {
        const result = await plaidPost('/accounts/balance/get', {
          access_token: item.accessToken
        });
        accounts.push(...normalizeAccounts(result.accounts, item.itemId).map((account) => ({
          ...account,
          connectionLabel: item.label || null
        })));
      }

      return json(res, 200, { accounts, connectedItems: items.length });
    }

    if (req.method === 'GET' && req.url === '/api/plaid/items') {
      return json(res, 200, {
        items: store.load().map(({ itemId, label, createdAt }) => ({ itemId, label, createdAt }))
      });
    }

    return json(res, 404, { error: 'Not found' });
  } catch (error) {
    return json(res, 500, {
      error: error.message || 'Server error',
      plaidErrorCode: error.plaid?.error_code || null
    });
  }
}

if (require.main === module) {
  http.createServer(handler).listen(port, '0.0.0.0', () => {
    console.log(`BillNest Plaid backend listening on port ${port}`);
  });
}

module.exports = { handler, loadEnvFile };
