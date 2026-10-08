#!/usr/bin/env node
// Repeatable load scenarios for Let's Travel (docs/operations.md, "Load tests").
// No dependencies: Node 22+ only.
//
//   node scripts/load/load.mjs --scenario mixed --concurrency 20 --duration 60
//
// Scenarios: search, autocomplete, dashboards, subscriptions, mixed, all.
// Environment:
//   BASE_URL         gateway or proxy URL (default https://localhost:8443)
//   INSECURE_TLS=1   accept the local self-signed certificate
//   ADMIN_EMAIL / ADMIN_PASSWORD   an admin account (dashboards scenario, setup checks)
//   LOAD_PASSWORD    password for the traveler accounts the run registers (default generated)
// Each run registers its own traveler accounts (load-<run>-<n>@example.test), so runs never
// interfere with each other or with real users.

import { execSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import os from "node:os";
import { randomUUID } from "node:crypto";

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, index, all) => {
  if (value.startsWith("--")) pairs.push([value.slice(2), all[index + 1] && !all[index + 1].startsWith("--") ? all[index + 1] : "true"]);
  return pairs;
}, []));
const scenarioName = args.scenario ?? "mixed";
const concurrency = Number(args.concurrency ?? 10);
const durationSeconds = Number(args.duration ?? 30);
const budgetMs = Number(args["budget-ms"] ?? 5000);
const outDir = args.out ?? "load-results";
const baseUrl = (process.env.BASE_URL ?? "https://localhost:8443").replace(/\/+$/, "");
if (process.env.INSECURE_TLS === "1") process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const words = ["crete", "rome", "lisbon", "beach", "museum", "hiking", "train", "island", "food", "paris", "alps", "sea"];
const pick = list => list[Math.floor(Math.random() * list.length)];

/** One timed HTTP call; never throws. */
async function call(operation, path, { method = "GET", token, body, headers = {} } = {}) {
  const started = performance.now();
  let status = 0;
  let data = null;
  try {
    const response = await fetch(baseUrl + path, {
      method,
      headers: { "X-Request-ID": `load-${randomUUID()}`, ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
    status = response.status;
    const text = await response.text();
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  } catch {
    status = 0;
  }
  record(operation, performance.now() - started, status);
  return { status, data };
}

const samples = new Map();
let recording = false;
function record(operation, ms, status) {
  if (!recording) return;
  if (!samples.has(operation)) samples.set(operation, { latencies: [], statuses: {} });
  const entry = samples.get(operation);
  entry.latencies.push(ms);
  entry.statuses[status] = (entry.statuses[status] ?? 0) + 1;
}

async function login(username, password) {
  const { status, data } = await call("setup", "/api/auth/login", { method: "POST", body: { username, password } });
  if (status !== 200) throw new Error(`Sign-in failed for ${username} (HTTP ${status})`);
  return data.accessToken;
}

async function travelers(count) {
  const run = Date.now().toString(36);
  const password = process.env.LOAD_PASSWORD ?? `Load-${randomUUID()}`;
  const tokens = [];
  for (let n = 0; n < count; n++) {
    const email = `load-${run}-${n}@example.test`;
    const { status } = await call("setup", "/api/registrations", { method: "POST",
      body: { email, firstName: "Load", lastName: `Traveler ${n}`, password } });
    if (status !== 201 && status !== 200) throw new Error(`Registration failed (HTTP ${status}); is self sign-up enabled?`);
    tokens.push(await login(email, password));
  }
  return tokens;
}

async function bookableTravel(token) {
  const { status, data } = await call("setup", "/api/travels?page=0&size=100", { token });
  if (status !== 200) throw new Error(`Could not list travels (HTTP ${status})`);
  const best = [...data.items].sort((a, b) => b.remainingCapacity - a.remainingCapacity)[0];
  if (!best || best.remainingCapacity < concurrency) {
    throw new Error(`The subscriptions scenario needs a published travel with at least ${concurrency} free places.`);
  }
  return best;
}

const scenarios = {
  search: async ({ token }) => {
    await call("search", `/api/search/travels?q=${pick(words)}&page=0&size=10`, { token });
  },
  autocomplete: async ({ token }) => {
    const word = pick(words);
    await call("autocomplete", `/api/search/autocomplete?q=${word.slice(0, 2 + Math.floor(Math.random() * 3))}&limit=8`, { token });
  },
  dashboards: async ({ token, adminToken }) => {
    if (adminToken && Math.random() < 0.5) await call("dashboard-admin", "/api/dashboards/admin?months=6&top=5", { token: adminToken });
    else await call("dashboard-me", "/api/dashboards/me", { token });
  },
  subscriptions: async ({ token, travel }) => {
    const reserved = await call("subscribe", "/api/subscriptions", { method: "POST", token, body: { travelId: travel.id },
      headers: { "Idempotency-Key": randomUUID() } });
    if (reserved.status === 201 || reserved.status === 200) {
      await call("cancel", `/api/subscriptions/${reserved.data.subscriptionId}/cancellation`, { method: "POST", token,
        headers: { "Idempotency-Key": randomUUID() } });
    }
  },
  mixed: async context => {
    await scenarios.search(context);
    await scenarios.autocomplete(context);
    if (context.travel) await call("travel-detail", `/api/travels/${context.travel.id}`, { token: context.token });
    await call("recommendations", "/api/recommendations?limit=6", { token: context.token });
    await call("my-subscriptions", "/api/subscriptions?page=0&size=20", { token: context.token });
    await call("dashboard-me", "/api/dashboards/me", { token: context.token });
  },
};

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

async function runScenario(name, contexts) {
  samples.clear();
  recording = true;
  const until = Date.now() + durationSeconds * 1000;
  const started = performance.now();
  await Promise.all(contexts.map(async context => {
    while (Date.now() < until) await scenarios[name](context);
  }));
  recording = false;
  const elapsed = (performance.now() - started) / 1000;
  const operations = [...samples.entries()].map(([operation, entry]) => {
    const sorted = [...entry.latencies].sort((a, b) => a - b);
    const errors = Object.entries(entry.statuses).filter(([status]) => !(status >= 200 && status < 400))
      .reduce((sum, [, count]) => sum + count, 0);
    return { operation, requests: sorted.length, throughputPerSecond: +(sorted.length / elapsed).toFixed(1),
      errors, errorRate: +(errors / sorted.length).toFixed(4), statuses: entry.statuses,
      p50Ms: Math.round(percentile(sorted, 50)), p95Ms: Math.round(percentile(sorted, 95)),
      p99Ms: Math.round(percentile(sorted, 99)), maxMs: Math.round(sorted.at(-1) ?? 0) };
  });
  const passed = operations.every(op => op.p99Ms < budgetMs && op.errorRate < 0.01);
  return { scenario: name, seconds: +elapsed.toFixed(1), concurrency, budgetMs, passed, operations };
}

function environment() {
  let commit = "unknown";
  try { commit = execSync("git rev-parse --short HEAD", { stdio: ["ignore", "pipe", "ignore"] }).toString().trim(); } catch { /* not a checkout */ }
  return { at: new Date().toISOString(), baseUrl, commit, node: process.version, platform: `${os.type()} ${os.release()}`,
    cpus: os.cpus().length, memoryGb: +(os.totalmem() / 2 ** 30).toFixed(1) };
}

async function main() {
  const names = scenarioName === "all" ? ["search", "autocomplete", "dashboards", "subscriptions", "mixed"] : [scenarioName];
  if (names.some(name => !scenarios[name])) throw new Error(`Unknown scenario ${scenarioName}`);
  console.log(`Preparing ${concurrency} traveler accounts against ${baseUrl} …`);
  const tokens = await travelers(concurrency);
  const adminToken = process.env.ADMIN_EMAIL && process.env.ADMIN_PASSWORD
    ? await login(process.env.ADMIN_EMAIL, process.env.ADMIN_PASSWORD) : null;
  const needsTravel = names.includes("subscriptions") || names.includes("mixed");
  const travel = needsTravel ? await bookableTravel(tokens[0]).catch(error => {
    if (names.includes("subscriptions")) throw error;
    return null;
  }) : null;
  const contexts = tokens.map(token => ({ token, adminToken, travel }));

  const results = [];
  for (const name of names) {
    console.log(`Running ${name} for ${durationSeconds}s with ${concurrency} concurrent users …`);
    const result = await runScenario(name, contexts);
    results.push(result);
    console.table(result.operations.map(({ statuses, ...row }) => row));
    console.log(`${name}: ${result.passed ? "PASS" : "FAIL"} (p99 under ${budgetMs} ms and under 1% errors for every operation)`);
  }
  const report = { environment: environment(), results };
  mkdirSync(outDir, { recursive: true });
  const file = `${outDir}/${report.environment.at.replaceAll(":", "-")}-${scenarioName}.json`;
  writeFileSync(file, JSON.stringify(report, null, 2));
  console.log(`Saved ${file}`);
  process.exitCode = results.every(result => result.passed) ? 0 : 1;
}

main().catch(error => { console.error(error.message); process.exitCode = 2; });
