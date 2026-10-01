import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmdirSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

const script = resolve('scripts/sandbox/create-sim-env.mjs');

test('generator writes synthetic credentials and refuses to overwrite them', () => {
  const directory = mkdtempSync(join(tmpdir(), 'travel-plan-payment-sim-'));
  const envPath = join(directory, '.env.payment-sim');
  try {
    const created = spawnSync(process.execPath, [script], { cwd: directory, encoding: 'utf8' });
    assert.equal(created.status, 0, created.stderr);
    const values = Object.fromEntries(readFileSync(envPath, 'utf8').trim().split('\n')
      .map(line => line.trim().split(/=(.*)/s).slice(0, 2)));
    assert.match(values.STRIPE_SANDBOX_SECRET_KEY, /^sk_test_sim_[a-f0-9]{32,}$/);
    assert.match(values.PAYPAL_SANDBOX_CLIENT_ID, /^sim_client_[a-f0-9]{32,}$/);
    assert.match(values.PAYPAL_SANDBOX_CLIENT_SECRET, /^sim_secret_[a-f0-9]{32,}$/);
    assert.ok(values.AUTH_TOKEN_SECRET.length >= 32);
    assert.notEqual(values.PAYPAL_SANDBOX_CLIENT_ID, values.PAYPAL_SANDBOX_CLIENT_SECRET);

    const repeated = spawnSync(process.execPath, [script], { cwd: directory, encoding: 'utf8' });
    assert.notEqual(repeated.status, 0);
    assert.equal(readFileSync(envPath, 'utf8').includes(values.STRIPE_SANDBOX_SECRET_KEY), true);
  } finally {
    if (existsSync(envPath)) unlinkSync(envPath);
    rmdirSync(directory);
  }
});
