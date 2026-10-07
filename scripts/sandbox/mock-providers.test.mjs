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

test('Stripe PaymentIntents and refunds are idempotent per key', async () => {
  const headers = {
    Authorization: 'Bearer sk_test_sim_node_test',
    'Content-Type': 'application/x-www-form-urlencoded',
  };
  const form = new URLSearchParams({
    amount: '45050', currency: 'eur', customer: 'cus_simulated001', payment_method: 'pm_simulatedvisa001',
    confirm: 'true', off_session: 'true', 'metadata[payment_id]': 'payment-1',
  }).toString();
  const charge = key => fetch(base + '/v1/payment_intents', {
    method: 'POST', headers: { ...headers, 'Idempotency-Key': key }, body: form,
  });

  const first = await (await charge('charge-1')).json();
  assert.equal(first.status, 'succeeded');
  assert.equal(first.amount, 45050);
  assert.equal(first.metadata.payment_id, 'payment-1');
  assert.deepEqual(await (await charge('charge-1')).json(), first);
  assert.notEqual((await (await charge('charge-2')).json()).id, first.id);

  const declined = await fetch(base + '/v1/payment_intents', {
    method: 'POST', headers: { ...headers, 'Idempotency-Key': 'charge-3' },
    body: form.replace('pm_simulatedvisa001', 'pm_other'),
  });
  assert.equal(declined.status, 402);

  const refund = body => fetch(base + '/v1/refunds', {
    method: 'POST', headers: { ...headers, 'Idempotency-Key': 'refund-1' }, body,
  });
  const refunded = await (await refund(`payment_intent=${first.id}`)).json();
  assert.equal(refunded.status, 'succeeded');
  assert.deepEqual(await (await refund(`payment_intent=${first.id}`)).json(), refunded);
});

test('PayPal orders capture the vaulted card once per request id and can be refunded', async () => {
  const basic = Buffer.from('sim_client_node_test:sim_secret_node_test').toString('base64');
  const token = (await (await fetch(base + '/v1/oauth2/token', {
    method: 'POST', headers: { Authorization: `Basic ${basic}` },
  })).json()).access_token;
  const order = vault => JSON.stringify({
    intent: 'CAPTURE',
    purchase_units: [{ custom_id: 'payment-2', amount: { currency_code: 'EUR', value: '450.50' } }],
    payment_source: { card: { vault_id: vault } },
  });
  const create = (key, vault = 'VAULT_SIM_001') => fetch(base + '/v2/checkout/orders', {
    method: 'POST', body: order(vault),
    headers: { Authorization: `Bearer ${token}`, 'PayPal-Request-Id': key, 'Content-Type': 'application/json' },
  });

  const created = await create('order-1');
  assert.equal(created.status, 201);
  const first = await created.json();
  const capture = first.purchase_units[0].payments.captures[0];
  assert.equal(capture.status, 'COMPLETED');
  assert.deepEqual(capture.amount, { currency_code: 'EUR', value: '450.50' });
  assert.equal(first.purchase_units[0].custom_id, 'payment-2');
  assert.deepEqual(await (await create('order-1')).json(), first);
  assert.equal((await create('order-2', 'VAULT_OTHER')).status, 422);

  const refund = await fetch(base + `/v2/payments/captures/${capture.id}/refund`, {
    method: 'POST', body: '{}',
    headers: { Authorization: `Bearer ${token}`, 'PayPal-Request-Id': 'refund-2', 'Content-Type': 'application/json' },
  });
  assert.equal(refund.status, 201);
  assert.equal((await refund.json()).status, 'COMPLETED');
});
