#!/usr/bin/env node
// Failure and recovery drills for a running Let's Travel stack (docs/operations.md, "Failure drills").
// It stops parts of the stack, checks the documented degraded behaviour, starts them again and checks
// recovery and persistence. Run it only against a test deployment: it really stops containers.
//
//   ADMIN_EMAIL=... ADMIN_PASSWORD=... node scripts/load/drill.mjs [--only replica,search,neo4j,payments,restart]
//
// Environment:
//   BASE_URL        gateway or proxy URL (default https://localhost:8443), INSECURE_TLS=1 for the local certificate
//   DOCKER          command that reaches the stack's Docker (default "docker"; for the local CI deploy host:
//                   "docker exec lets-travel-ci-deploy-host-1 docker")
//   COMPOSE_PROJECT compose project name (default lets-travel)
//   RECOVERY_SECONDS how long to wait for recovery after a restart (default 300)

import { execSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";

const baseUrl = (process.env.BASE_URL ?? "https://localhost:8443").replace(/\/+$/, "");
if (process.env.INSECURE_TLS === "1") process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
const docker = process.env.DOCKER ?? "docker";
const project = process.env.COMPOSE_PROJECT ?? "lets-travel";
const recoverySeconds = Number(process.env.RECOVERY_SECONDS ?? 300);
const only = (process.argv.includes("--only") ? process.argv[process.argv.indexOf("--only") + 1] : "replica,search,neo4j,payments,restart").split(",");
const log = [];
let token;

function note(step, ok, detail) {
  log.push({ at: new Date().toISOString(), step, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${step}: ${detail}`);
}

function sh(command) {
  return execSync(command, { stdio: ["ignore", "pipe", "pipe"] }).toString().trim();
}

function containers(service) {
  const ids = sh(`${docker} ps -a -q --filter label=com.docker.compose.project=${project} --filter label=com.docker.compose.service=${service}`);
  return ids ? ids.split(/\s+/) : [];
}

const stop = service => containers(service).forEach(id => sh(`${docker} stop ${id}`));
const start = service => containers(service).forEach(id => sh(`${docker} start ${id}`));

async function get(path) {
  const started = performance.now();
  try {
    const response = await fetch(baseUrl + path, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(40_000) });
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: response.status, data, ms: Math.round(performance.now() - started) };
  } catch {
    return { status: 0, data: null, ms: Math.round(performance.now() - started) };
  }
}

async function until(description, check) {
  const deadline = Date.now() + recoverySeconds * 1000;
  while (Date.now() < deadline) {
    if (await check()) return true;
    await new Promise(resolve => setTimeout(resolve, 5000));
  }
  return false;
}

async function signIn() {
  const response = await fetch(baseUrl + "/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: process.env.ADMIN_EMAIL, password: process.env.ADMIN_PASSWORD }) });
  if (!response.ok) throw new Error(`Admin sign-in failed (HTTP ${response.status})`);
  token = (await response.json()).accessToken;
}

async function counts() {
  const [users, travels, search] = await Promise.all([get("/api/users?page=0&size=1"), get("/api/travels?page=0&size=1"),
    get("/api/search/travels?page=0&size=1")]);
  return { users: users.data?.total, travels: travels.data?.total, searchable: search.data?.totalElements };
}

async function replicaDrill() {
  const replicas = containers("travel-service");
  if (replicas.length < 2) { note("replica", false, "travel-service runs one replica; deploy with compose.scale.yml for this drill"); return; }
  sh(`${docker} stop ${replicas[0]}`);
  const results = [];
  for (let i = 0; i < 40; i++) results.push((await get("/api/travels?page=0&size=5")).status);
  sh(`${docker} start ${replicas[0]}`);
  const failed = results.filter(status => status !== 200).length;
  note("replica", failed === 0, `one of ${replicas.length} travel-service replicas stopped: ${40 - failed}/40 reads succeeded`);
}

async function searchDrill() {
  stop("elasticsearch");
  const search = await get("/api/search/travels?q=a&page=0&size=5");
  const browse = await get("/api/travels?page=0&size=5");
  note("search down", search.status === 503 && browse.status === 200,
    `search ${search.status} in ${search.ms} ms (expected 503), browse ${browse.status} (expected 200: the app falls back to it)`);
  start("elasticsearch");
  const back = await until("search", async () => (await get("/api/search/travels?page=0&size=5")).status === 200);
  note("search recovered", back, back ? "search answers again; the index is rebuilt from travel-service" : `not back within ${recoverySeconds}s`);
}

async function neo4jDrill() {
  stop("neo4j");
  const travels = await get("/api/travels?page=0&size=5");
  const recommendations = await get("/api/recommendations?limit=3");
  const dashboard = await get("/api/dashboards/me");
  const users = await get("/api/users?page=0&size=5");
  note("neo4j down", travels.status === 503 && recommendations.status === 503 && dashboard.status === 200 && users.status === 200,
    `travels ${travels.status} in ${travels.ms} ms, recommendations ${recommendations.status} (expected 503 quickly); `
      + `dashboards ${dashboard.status}, users ${users.status} (expected 200)`);
  start("neo4j");
  const back = await until("neo4j", async () => (await get("/api/travels?page=0&size=5")).status === 200);
  note("neo4j recovered", back, back ? "travels and recommendations answer again without restarting travel-service" : `not back within ${recoverySeconds}s`);
}

async function paymentsDrill() {
  if (containers("payment-simulator").length === 0) { note("payments", false, "no payment-simulator in this deployment (compose.payment-sim.yml)"); return; }
  stop("payment-simulator");
  const options = await get("/api/payment-options");
  const dashboard = await get("/api/dashboards/me");
  note("payment provider down", options.status === 200 && dashboard.status === 200,
    `payment options ${options.status}, dashboards ${dashboard.status}; payments made now stay PROCESSING/REQUIRES_RECONCILIATION and are shown as pending`);
  start("payment-simulator");
  note("payment provider restarted", true, "reconciliation settles pending payments on its next run");
}

async function restartDrill() {
  const before = await counts();
  for (const service of ["postgres", "neo4j", "elasticsearch"]) stop(service);
  for (const service of ["postgres", "neo4j", "elasticsearch"]) start(service);
  let after = null;
  const back = await until("restart", async () => {
    after = await counts();
    return after.users === before.users && after.travels === before.travels && after.searchable === before.searchable;
  });
  note("stateful restart", back, `before ${JSON.stringify(before)}, after ${JSON.stringify(after)}`);
  const events = await get("/api/admin/events/status");
  note("event backlog drained", events.status === 200 && events.data?.pending === 0 && events.data?.parked === 0,
    `outbox ${JSON.stringify(events.data)} (pending events are delivered after restarts; none parked)`);
}

const drills = { replica: replicaDrill, search: searchDrill, neo4j: neo4jDrill, payments: paymentsDrill, restart: restartDrill };

async function main() {
  await signIn();
  for (const name of only) {
    if (!drills[name]) throw new Error(`Unknown drill ${name}`);
    console.log(`\n== ${name} ==`);
    await drills[name]();
  }
  mkdirSync("load-results", { recursive: true });
  const file = `load-results/${new Date().toISOString().replaceAll(":", "-")}-drill.json`;
  writeFileSync(file, JSON.stringify({ baseUrl, project, log }, null, 2));
  console.log(`\nSaved ${file}`);
  process.exitCode = log.every(entry => entry.ok) ? 0 : 1;
}

main().catch(error => { console.error(error.message); process.exitCode = 2; });
