import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const randomValue = () => randomBytes(24).toString('hex');
const values = {
  POSTGRES_SUPERUSER: 'travelplan',
  POSTGRES_SUPERUSER_PASSWORD: randomValue(),
  AUTH_DB_PASSWORD: randomValue(),
  USERS_DB_PASSWORD: randomValue(),
  PAYMENTS_DB_PASSWORD: randomValue(),
  NEO4J_PASSWORD: randomValue(),
  AUTH_TOKEN_SECRET: randomValue(),
  ADMIN_USERNAME: 'sim-admin',
  ADMIN_PASSWORD: randomValue(),
  AUTH_INTERNAL_SERVICE_TOKEN: randomValue(),
  STRIPE_SANDBOX_SECRET_KEY: `sk_test_sim_${randomValue()}`,
  PAYPAL_SANDBOX_CLIENT_ID: `sim_client_${randomValue()}`,
  PAYPAL_SANDBOX_CLIENT_SECRET: `sim_secret_${randomValue()}`,
};

const destination = resolve('.env.payment-sim');
writeFileSync(destination, Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', {
  encoding: 'utf8', flag: 'wx', mode: 0o600,
});
process.stdout.write(`Created ${destination} with synthetic credentials.\n`);
process.stdout.write('Fake references: pm_simulatedvisa001, VAULT_SIM_001\n');
