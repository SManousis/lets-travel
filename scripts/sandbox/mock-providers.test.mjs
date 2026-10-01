import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { createPaymentSimulator } from './mock-providers.mjs';

let server;
let base;

beforeEach(async () => {
  server = createPaymentSimulator({
    stripeSecret: 'sk_test_sim_node_test',
    paypalClientId: 'sim_client_node_test',
    paypalClientSecret: 'sim_secret_node_test',
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

afterEach(async () => {
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
});

test('Stripe fixture requires its synthetic key and becomes detached', async () => {
  const path = '/v1/payment_methods/pm_simulatedvisa001';
  const headers = { Authorization: 'Bearer sk_test_sim_node_test' };
  const denied = await fetch(base + path);
  assert.equal(denied.status, 401);

  const found = await fetch(base + path, { headers });
  assert.equal(found.status, 200);
  assert.deepEqual(await found.json(), {
    id: 'pm_simulatedvisa001', livemode: false, customer: 'cus_simulated001',
    type: 'card', card: { brand: 'visa', last4: '4242' },
  });

  const detached = await fetch(base + path + '/detach', { method: 'POST', headers });
  assert.equal(detached.status, 200);
  const after = await fetch(base + path, { headers });
  assert.equal((await after.json()).customer, null);
  assert.equal((await fetch(base + '/v1/payment_methods/pm_unknown123', { headers })).status, 404);
});

test('PayPal fixture requires OAuth and disappears after vault deletion', async () => {
  const basic = Buffer.from('sim_client_node_test:sim_secret_node_test').toString('base64');
  const denied = await fetch(base + '/v1/oauth2/token', { method: 'POST' });
  assert.equal(denied.status, 401);

  const oauth = await fetch(base + '/v1/oauth2/token', {
    method: 'POST', headers: { Authorization: `Basic ${basic}` },
  });
  assert.equal(oauth.status, 200);
  const token = (await oauth.json()).access_token;
  assert.match(token, /^sim_access_[a-f0-9]{32,}$/);

  const path = '/v3/vault/payment-tokens/VAULT_SIM_001';
  const headers = { Authorization: `Bearer ${token}` };
  assert.equal((await fetch(base + path, {
    headers: { Authorization: 'Bearer sim_access_token' },
  })).status, 401);
  const found = await fetch(base + path, { headers });
  assert.equal(found.status, 200);
  assert.deepEqual(await found.json(), {
    id: 'VAULT_SIM_001', customer: { id: 'PAYPAL_SIM_CUSTOMER_001' },
    payment_source: { card: { brand: 'VISA', last_digits: '1111' } },
  });

  const deleted = await fetch(base + path, { method: 'DELETE', headers });
  assert.equal(deleted.status, 204);
  assert.equal((await fetch(base + path, { headers })).status, 404);
});
