import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const STRIPE_REFERENCE = 'pm_simulatedvisa001';
const PAYPAL_REFERENCE = 'VAULT_SIM_001';

async function readBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

const simulatedId = prefix => `${prefix}${randomBytes(12).toString('hex')}`;

function send(response, status, body = {}) {
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
  });
  response.end(status === 204 ? undefined : JSON.stringify(body));
}

export function createPaymentSimulator({ stripeSecret, paypalClientId, paypalClientSecret }) {
  if (!stripeSecret?.startsWith('sk_test_sim_')
      || !paypalClientId?.startsWith('sim_client_')
      || !paypalClientSecret?.startsWith('sim_secret_')) {
    throw new Error('The local simulator accepts synthetic credentials only');
  }

  const stripeBearer = `Bearer ${stripeSecret}`;
  const paypalBasic = `Basic ${Buffer.from(`${paypalClientId}:${paypalClientSecret}`).toString('base64')}`;
  const paypalAccessToken = `sim_access_${randomBytes(24).toString('hex')}`;
  let stripeAttached = true;
  let paypalPresent = true;
  // Like the real providers, a repeated idempotency key returns the first answer instead of a new charge.
  const stripeIntents = new Map();
  const stripeRefunds = new Map();
  const paypalOrders = new Map();
  const paypalRefunds = new Map();
  const captures = new Set();

  return createServer(async (request, response) => {
    const path = new URL(request.url, 'http://payment-simulator').pathname;
    if (request.method === 'GET' && path === '/health') {
      send(response, 200, { status: 'ok' });
      return;
    }

    if (path === `/v1/payment_methods/${STRIPE_REFERENCE}`
        || path === `/v1/payment_methods/${STRIPE_REFERENCE}/detach`) {
      if (request.headers.authorization !== stripeBearer) return send(response, 401);
      if (request.method === 'GET' && path.endsWith(STRIPE_REFERENCE)) {
        return send(response, 200, {
          id: STRIPE_REFERENCE,
          livemode: false,
          customer: stripeAttached ? 'cus_simulated001' : null,
          type: 'card',
          card: { brand: 'visa', last4: '4242' },
        });
      }
      if (request.method === 'POST' && path.endsWith('/detach')) {
        stripeAttached = false;
        return send(response, 200, { id: STRIPE_REFERENCE, livemode: false, customer: null });
      }
      return send(response, 405);
    }

    if (path === '/v1/payment_intents' || path === '/v1/refunds') {
      if (request.method !== 'POST') return send(response, 405);
      if (request.headers.authorization !== stripeBearer) return send(response, 401);
      const key = request.headers['idempotency-key'];
      if (!key) return send(response, 400, { error: { type: 'invalid_request_error' } });
      const form = new URLSearchParams(await readBody(request));
      if (path === '/v1/refunds') {
        if (![...stripeIntents.values()].some(intent => intent.id === form.get('payment_intent'))) {
          return send(response, 404, { error: { type: 'invalid_request_error' } });
        }
        if (!stripeRefunds.has(key)) stripeRefunds.set(key, { id: simulatedId('re_sim_'), status: 'succeeded' });
        return send(response, 200, stripeRefunds.get(key));
      }
      if (!stripeIntents.has(key)) {
        if (form.get('payment_method') !== STRIPE_REFERENCE || !stripeAttached) {
          return send(response, 402, { error: { type: 'card_error', code: 'card_declined' } });
        }
        stripeIntents.set(key, {
          id: simulatedId('pi_sim_'), object: 'payment_intent', livemode: false, status: 'succeeded',
          amount: Number(form.get('amount')), currency: form.get('currency'),
          metadata: { payment_id: form.get('metadata[payment_id]') },
        });
      }
      return send(response, 200, stripeIntents.get(key));
    }

    if (path === '/v2/checkout/orders' || /^\/v2\/payments\/captures\/[A-Za-z0-9_-]+\/refund$/.test(path)) {
      if (request.method !== 'POST') return send(response, 405);
      if (request.headers.authorization !== `Bearer ${paypalAccessToken}`) return send(response, 401);
      const key = request.headers['paypal-request-id'];
      if (!key) return send(response, 400, { name: 'INVALID_REQUEST' });
      if (path !== '/v2/checkout/orders') {
        if (!captures.has(path.split('/')[4])) return send(response, 404, { name: 'RESOURCE_NOT_FOUND' });
        if (!paypalRefunds.has(key)) paypalRefunds.set(key, { id: simulatedId('REFUND_SIM_'), status: 'COMPLETED' });
        return send(response, 201, paypalRefunds.get(key));
      }
      let order;
      try { order = JSON.parse(await readBody(request)); } catch { return send(response, 400, { name: 'INVALID_REQUEST' }); }
      if (!paypalOrders.has(key)) {
        if (order.payment_source?.card?.vault_id !== PAYPAL_REFERENCE || !paypalPresent) {
          return send(response, 422, { name: 'UNPROCESSABLE_ENTITY', details: [{ issue: 'INSTRUMENT_DECLINED' }] });
        }
        const unit = order.purchase_units?.[0] ?? {};
        const captureId = simulatedId('CAPTURE_SIM_');
        captures.add(captureId);
        paypalOrders.set(key, {
          id: simulatedId('ORDER_SIM_'), status: 'COMPLETED',
          purchase_units: [{ custom_id: unit.custom_id, payments: { captures: [
            { id: captureId, status: 'COMPLETED', amount: unit.amount },
          ] } }],
        });
      }
      return send(response, 201, paypalOrders.get(key));
    }

    if (path === '/v1/oauth2/token') {
      if (request.method !== 'POST') return send(response, 405);
      if (request.headers.authorization !== paypalBasic) return send(response, 401);
      return send(response, 200, { access_token: paypalAccessToken, token_type: 'Bearer', expires_in: 3600 });
    }

    if (path === `/v3/vault/payment-tokens/${PAYPAL_REFERENCE}`) {
      if (request.headers.authorization !== `Bearer ${paypalAccessToken}`) return send(response, 401);
      if (!paypalPresent) return send(response, 404);
      if (request.method === 'GET') {
        return send(response, 200, {
          id: PAYPAL_REFERENCE,
          customer: { id: 'PAYPAL_SIM_CUSTOMER_001' },
          payment_source: { card: { brand: 'VISA', last_digits: '1111' } },
        });
      }
      if (request.method === 'DELETE') {
        paypalPresent = false;
        return send(response, 204);
      }
      return send(response, 405);
    }

    return send(response, 404);
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const server = createPaymentSimulator({
    stripeSecret: process.env.SIM_STRIPE_SECRET,
    paypalClientId: process.env.SIM_PAYPAL_CLIENT_ID,
    paypalClientSecret: process.env.SIM_PAYPAL_CLIENT_SECRET,
  });
  server.listen(Number(process.env.PORT || 8085), '0.0.0.0', () => {
    process.stdout.write('Local payment simulator ready\n');
  });
}
