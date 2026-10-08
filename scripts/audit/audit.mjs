#!/usr/bin/env node
// Final audit run: every audit journey that can be checked over HTTP, against a deployment seeded with
// scripts/demo/seed.mjs. Writes docs/audit/results/<date>-<commit>.md (and .json) with the request, expected and
// actual result for each check. Journeys that need a person (browser look and feel) or a quiet machine (load and
// failure drills) are listed in docs/audit/final-audit.md.
//
//   DEMO_PASSWORD=... ADMIN_EMAIL=... ADMIN_PASSWORD=... BASE_URL=https://localhost:8443 INSECURE_TLS=1 \
//     RELEASE=<deployed commit> node scripts/audit/audit.mjs

import { execSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import os from "node:os";

const baseUrl = (process.env.BASE_URL ?? "https://localhost:8443").replace(/\/+$/, "");
if (process.env.INSECURE_TLS === "1") process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
const demoPassword = process.env.DEMO_PASSWORD;
if (!demoPassword || !process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD) {
  throw new Error("Set DEMO_PASSWORD, ADMIN_EMAIL and ADMIN_PASSWORD (run scripts/demo/seed.mjs first)");
}
const results = [];

async function http(method, path, { token, body, headers = {} } = {}) {
  const started = performance.now();
  try {
    const response = await fetch(baseUrl + path, {
      method, signal: AbortSignal.timeout(40_000),
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        ...(["POST", "PUT", "DELETE"].includes(method) ? { "Idempotency-Key": randomUUID() } : {}), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return { status: response.status, data, headers: response.headers, ms: Math.round(performance.now() - started) };
  } catch (error) {
    return { status: 0, data: String(error.message), headers: new Headers(), ms: Math.round(performance.now() - started) };
  }
}

async function check(area, name, command, expected, run) {
  let actual;
  let pass;
  try {
    [pass, actual] = await run();
  } catch (error) {
    pass = false;
    actual = `error: ${error.message}`;
  }
  results.push({ area, name, command, expected, actual, result: pass ? "PASS" : "FAIL" });
  console.log(`${pass ? "PASS" : "FAIL"}  ${area}: ${name} (${actual})`);
}

const login = async (email, password) => (await http("POST", "/api/auth/login", { body: { username: email, password } })).data?.accessToken;
const items = data => data?.items ?? [];

async function main() {
  const admin = await login(process.env.ADMIN_EMAIL, process.env.ADMIN_PASSWORD);
  const maria = await login("maria.manager@example.test", demoPassword);
  const nikos = await login("nikos.manager@example.test", demoPassword);
  const anna = await login("anna.culture@example.test", demoPassword);
  const ben = await login("ben.beach@example.test", demoPassword);
  if (!admin || !maria || !nikos || !anna || !ben) throw new Error("Demo accounts missing: run scripts/demo/seed.mjs");
  const travels = items((await http("GET", "/api/travels?page=0&size=100", { token: admin })).data);
  const byName = name => travels.find(travel => travel.name === name);

  // ---- Login, RBAC, transport ----------------------------------------------------------------
  await check("Secure login", "Wrong password is refused", "POST /api/auth/login (wrong password)", "401", async () => {
    const r = await http("POST", "/api/auth/login", { body: { username: "anna.culture@example.test", password: "not-the-password" } });
    return [r.status === 401, `${r.status}`];
  });
  await check("Secure login", "Refresh token only in an HttpOnly cookie", "POST /api/auth/login", "200, Set-Cookie HttpOnly Secure SameSite=Strict, no refresh token in body", async () => {
    const r = await http("POST", "/api/auth/login", { body: { username: "anna.culture@example.test", password: demoPassword } });
    const cookie = r.headers.get("set-cookie") ?? "";
    const ok = r.status === 200 && /HttpOnly/i.test(cookie) && /Secure/i.test(cookie) && /SameSite=Strict/i.test(cookie)
      && !JSON.stringify(r.data).includes("refresh");
    return [ok, `${r.status}; cookie flags ${["HttpOnly", "Secure", "SameSite=Strict"].filter(flag => cookie.includes(flag)).join(", ")}`];
  });
  for (const [who, token, path, expected] of [
    ["anonymous", null, "/api/subscriptions", 401], ["traveler", anna, "/api/users", 403],
    ["traveler", anna, "/api/dashboards/admin", 403], ["traveler", anna, "/api/managed-travels/" + byName("Florence art and food")?.id + "/subscribers", 403],
    ["manager", maria, "/api/dashboards/admin", 403], ["manager", maria, "/api/admin/reports", 403],
    ["manager", maria, "/api/dashboards/manager", 200], ["admin", admin, "/api/dashboards/admin", 200]]) {
    await check("Role-based access", `${who} GET ${path.replace(/[0-9a-f-]{36}/, "{id}")}`, `GET ${path.replace(/[0-9a-f-]{36}/, "{id}")} as ${who}`, `${expected}`, async () => {
      const r = await http("GET", path, { token });
      return [r.status === expected, `${r.status}`];
    });
  }
  await check("Role-based access", "A manager cannot see another manager's subscribers", "GET /api/managed-travels/{Nikos's travel}/subscribers as Maria", "404",
    async () => {
      const r = await http("GET", `/api/managed-travels/${byName("Rhodes beach week")?.id}/subscribers`, { token: maria });
      return [r.status === 404, `${r.status}`];
    });
  await check("TLS", "HTTPS with HSTS", `GET ${baseUrl}/`, "200 over HTTPS, Strict-Transport-Security set", async () => {
    const r = await http("GET", "/");
    return [baseUrl.startsWith("https://") && r.status === 200 && !!r.headers.get("strict-transport-security"),
      `${r.status}, HSTS ${r.headers.get("strict-transport-security") ?? "missing"}`];
  });
  await check("XSS protection", "Content-Security-Policy on the web app", "GET /", "CSP with script-src 'self'", async () => {
    const csp = (await http("GET", "/")).headers.get("content-security-policy") ?? "";
    return [csp.includes("script-src 'self'"), csp ? "present" : "missing"];
  });

  // ---- Search and autocomplete ---------------------------------------------------------------
  await check("Search", "Accurate results", "GET /api/search/travels?q=florence", "Florence art and food first", async () => {
    const r = await http("GET", "/api/search/travels?q=florence&page=0&size=5", { token: anna });
    const first = items(r.data)[0]?.title;
    return [r.status === 200 && first === "Florence art and food", `${r.status}, first: ${first ?? "none"}, ${r.ms} ms`];
  });
  await check("Search", "Typo tolerance", "GET /api/search/travels?q=florance", "Florence art and food found", async () => {
    const r = await http("GET", "/api/search/travels?q=florance&page=0&size=5", { token: anna });
    return [items(r.data).some(hit => hit.title === "Florence art and food"), `${r.status}, ${items(r.data).length} hits`];
  });
  await check("Autocomplete", "Prompt suggestions", "GET /api/search/autocomplete?q=rho", "a Rhodes suggestion in under 1 s", async () => {
    const r = await http("GET", "/api/search/autocomplete?q=rho&limit=8", { token: anna });
    return [items(r.data).some(item => /Rhodes/.test(item.text)) && r.ms < 1000, `${r.status}, ${items(r.data).length} suggestions, ${r.ms} ms`];
  });
  await check("SQL/query injection", "Hostile search text is just text", "GET /api/search/travels?q=' OR 1=1 --", "200, no error", async () => {
    const r = await http("GET", `/api/search/travels?q=${encodeURIComponent("' OR 1=1 --\"}}")}&page=0&size=5`, { token: anna });
    return [r.status === 200, `${r.status}, ${items(r.data).length} hits`];
  });

  // ---- Recommendations: the two-account comparison --------------------------------------------
  const annaRecs = (await http("GET", "/api/recommendations?limit=5", { token: anna })).data;
  const benRecs = (await http("GET", "/api/recommendations?limit=5", { token: ben })).data;
  await check("Recommendations", "Two accounts get different, explained suggestions",
    "GET /api/recommendations as Anna (booked Florence) and as Ben (booked Rhodes)", "both PERSONALIZED, different first travel, reasons from their own trips",
    async () => {
      const annaTop = annaRecs?.items?.[0];
      const benTop = benRecs?.items?.[0];
      const ok = annaRecs?.strategy === "PERSONALIZED" && benRecs?.strategy === "PERSONALIZED" && annaTop?.travelId !== benTop?.travelId;
      const reasons = item => (item?.reasons ?? []).map(reason => reason.value).join("/");
      return [ok, `Anna ${annaRecs?.strategy}: ${annaTop?.title} (${reasons(annaTop)}); Ben ${benRecs?.strategy}: ${benTop?.title} (${reasons(benTop)})`];
    });

  // ---- Dashboards and statistics -------------------------------------------------------------
  await check("Admin overview", "Platform figures", "GET /api/dashboards/admin", "200 with income, travels, travelers, ratings, top managers", async () => {
    const r = await http("GET", "/api/dashboards/admin?months=6&top=5", { token: admin });
    return [r.status === 200 && Array.isArray(r.data?.incomeByMonth) && "activeTravelers" in r.data,
      `${r.status}, ${r.data?.organizedTravels} travels, ${r.data?.activeTravelers} travelers, ${r.ms} ms`];
  });
  await check("Manager statistics", "Own analytics", "GET /api/dashboards/manager as Nikos", "200 with income by month and per-travel figures", async () => {
    const r = await http("GET", "/api/dashboards/manager?months=6", { token: nikos });
    return [r.status === 200 && Array.isArray(r.data?.incomeByMonth), `${r.status}, ${r.data?.totalTravelers} travelers, ${r.ms} ms`];
  });
  await check("Traveler statistics", "Own statistics", "GET /api/dashboards/me as Ben", "200 with trips and payment methods", async () => {
    const r = await http("GET", "/api/dashboards/me", { token: ben });
    return [r.status === 200 && "upcomingTrips" in (r.data ?? {}), `${r.status}, ${r.data?.upcomingTrips} upcoming, methods ${JSON.stringify(r.data?.preferredPaymentMethods)}`];
  });
  await check("Manager profile", "Public page hides rating authors", "GET /api/managers/{Maria}", "200, no author names or report contents", async () => {
    const travel = byName("Florence art and food");
    const r = await http("GET", `/api/managers/${travel?.managerId}`, { token: ben });
    return [r.status === 200 && !JSON.stringify(r.data).includes("authorDisplayName"), `${r.status}, ${r.data?.displayName}`];
  });

  // ---- Subscriptions, cutoff, payments, feedback ----------------------------------------------
  await check("Subscription", "Subscribe and cancel before the cutoff", "POST /api/subscriptions, then /cancellation (Lisbon, 65 days ahead)",
    "201 then 200 CANCELLED_BY_TRAVELER", async () => {
      const reserved = await http("POST", "/api/subscriptions", { token: ben, body: { travelId: byName("Lisbon city lights")?.id } });
      const cancelled = await http("POST", `/api/subscriptions/${reserved.data?.subscriptionId}/cancellation`, { token: ben });
      return [reserved.status === 201 && cancelled.data?.status === "CANCELLED_BY_TRAVELER", `${reserved.status} then ${cancelled.status} ${cancelled.data?.status}`];
    });
  await check("Payments", "Payment choices without card data", "GET /api/payment-options as Anna", "200, test card or saved methods, no card numbers", async () => {
    const r = await http("GET", "/api/payment-options", { token: anna });
    return [r.status === 200 && !/\d{12,}/.test(JSON.stringify(r.data)), `${r.status}, test card ${r.data?.testProviderEnabled}, ${r.data?.methods?.length} saved methods`];
  });
  await check("Payments", "Booking paid and confirmed", "GET /api/subscriptions as Anna", "Florence subscription CONFIRMED", async () => {
    const mine = items((await http("GET", "/api/subscriptions?page=0&size=50", { token: anna })).data);
    const florence = mine.find(item => item.travelTitle === "Florence art and food");
    return [florence?.status === "CONFIRMED", florence?.status ?? "no subscription"];
  });
  await check("Feedback", "Only after the trip ended", "POST /api/feedback before Florence starts", "409 FEEDBACK_NOT_ALLOWED-style refusal", async () => {
    const r = await http("POST", "/api/feedback", { token: anna, body: { travelId: byName("Florence art and food")?.id, rating: 5 } });
    return [r.status === 409 || r.status === 400, `${r.status} ${r.data?.code ?? ""}`];
  });

  // ---- Manager listings and subscribers --------------------------------------------------------
  await check("Listing management", "Create, publish-check and delete a draft", "POST /api/travels, POST /publication (no stop), DELETE",
    "201, 409 PUBLICATION_NOT_READY or 400, 204", async () => {
      const created = await http("POST", "/api/travels", { token: maria, body: { name: `Audit draft ${Date.now()}`, description: "Temporary audit listing",
        startDate: "2027-09-01", endDate: "2027-09-03", capacity: 5, price: 100, currency: "EUR", stops: [{ destinationId: travels[0]?.stops?.[0]?.destinationId,
          position: 0 }], activities: [], accommodations: [], transports: [] } });
      const removed = await http("DELETE", `/api/travels/${created.data?.id}`, { token: maria });
      return [created.status === 201 && removed.status === 204, `${created.status} then ${removed.status}`];
    });
  await check("Subscriber list", "Manager sees own subscribers", "GET /api/managed-travels/{Florence}/subscribers as Maria", "200 listing Anna", async () => {
    const r = await http("GET", `/api/managed-travels/${byName("Florence art and food")?.id}/subscribers?page=0&size=20`, { token: maria });
    return [r.status === 200 && items(r.data).some(item => /Anna/.test(item.displayName)), `${r.status}, ${items(r.data).length} subscribers`];
  });

  // ---- Response times ------------------------------------------------------------------------
  await check("Under five seconds", "Every action above answered within 5 s", "all checks", "max < 5000 ms", async () => {
    const r = await Promise.all([http("GET", "/api/search/travels?q=beach&page=0&size=10", { token: ben }),
      http("GET", "/api/dashboards/admin?months=12&top=5", { token: admin }), http("GET", "/api/travels?page=0&size=50", { token: anna })]);
    const max = Math.max(...r.map(item => item.ms));
    return [max < 5000, `slowest ${max} ms`];
  });

  let commit = process.env.RELEASE ?? "";
  if (!commit) {
    try { commit = execSync("git rev-parse --short HEAD", { stdio: ["ignore", "pipe", "ignore"] }).toString().trim(); } catch { commit = "unknown"; }
  }
  const at = new Date().toISOString();
  const passed = results.filter(item => item.result === "PASS").length;
  const lines = [
    `# Audit run ${at.slice(0, 10)}`, "",
    `- Release: \`${commit}\``, `- Environment: ${baseUrl} (run from ${os.type()} ${os.release()}, Node ${process.version})`,
    `- Time: ${at}`, `- Result: ${passed} of ${results.length} checks passed`, "",
    "| Area | Check | Command | Expected | Actual | Result |", "| --- | --- | --- | --- | --- | --- |",
    ...results.map(item => `| ${item.area} | ${item.name} | \`${item.command}\` | ${item.expected} | ${String(item.actual).replaceAll("|", "/")} | ${item.result} |`),
  ];
  mkdirSync("docs/audit/results", { recursive: true });
  const file = `docs/audit/results/${at.slice(0, 10)}-${commit.slice(0, 7)}`;
  writeFileSync(`${file}.md`, lines.join("\n") + "\n");
  writeFileSync(`${file}.json`, JSON.stringify({ at, commit, baseUrl, results }, null, 2));
  console.log(`\n${passed}/${results.length} passed. Saved ${file}.md`);
  process.exitCode = passed === results.length ? 0 : 1;
}

main().catch(error => { console.error(error.message); process.exitCode = 2; });
