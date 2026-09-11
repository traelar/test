const test = require('node:test');
const assert = require('node:assert/strict');

const { createPlaidClientConfig, normalizeAccounts } = require('../src/plaid');

test('createPlaidClientConfig reads credentials from environment only', () => {
  const config = createPlaidClientConfig({
    PLAID_CLIENT_ID: 'client-id',
    PLAID_SECRET: 'secret-value',
    PLAID_ENV: 'sandbox'
  });

  assert.equal(config.clientId, 'client-id');
  assert.equal(config.secret, 'secret-value');
  assert.equal(config.environment, 'sandbox');
});

test('createPlaidClientConfig rejects missing secret', () => {
  assert.throws(() => createPlaidClientConfig({ PLAID_CLIENT_ID: 'client-id' }), /PLAID_SECRET/);
});

test('normalizeAccounts returns app-safe account balance data', () => {
  const accounts = normalizeAccounts([
    {
      account_id: 'acc_1',
      name: 'Checking',
      official_name: 'Everyday Checking',
      mask: '1234',
      type: 'depository',
      subtype: 'checking',
      balances: { current: 350.25, available: 300.25 }
    }
  ]);

  assert.deepEqual(accounts, [{
    accountId: 'acc_1',
    name: 'Checking',
    officialName: 'Everyday Checking',
    mask: '1234',
    type: 'depository',
    subtype: 'checking',
    current: 350.25,
    available: 300.25
  }]);
});
