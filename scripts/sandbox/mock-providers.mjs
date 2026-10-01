import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const STRIPE_REFERENCE = 'pm_simulatedvisa001';
const PAYPAL_REFERENCE = 'VAULT_SIM_001';

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

  return createServer((request, response) => {
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
