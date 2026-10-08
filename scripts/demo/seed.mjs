#!/usr/bin/env node
// Deterministic demo data for every role, created through the public API only (docs/demo.md).
//
//   DEMO_PASSWORD=... ADMIN_EMAIL=... ADMIN_PASSWORD=... BASE_URL=https://localhost:8443 INSECURE_TLS=1 \
//     node scripts/demo/seed.mjs
//
// Everything uses @example.test addresses and the password given in DEMO_PASSWORD (never committed).
// Running it again finds what exists and changes nothing. Bookings are paid with the simulated test card,
// so the deployment must have PAYMENT_TEST_PROVIDER_ENABLED=true; without it the bookings stay unpaid.

import { randomUUID } from "node:crypto";

const baseUrl = (process.env.BASE_URL ?? "https://localhost:8443").replace(/\/+$/, "");
if (process.env.INSECURE_TLS === "1") process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
const password = process.env.DEMO_PASSWORD;
if (!password || password.length < 10) throw new Error("Set DEMO_PASSWORD (at least 10 characters)");
if (!process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD) throw new Error("Set ADMIN_EMAIL and ADMIN_PASSWORD");

export const MANAGERS = [
  { email: "maria.manager@example.test", firstName: "Maria", lastName: "Papadaki" },
  { email: "nikos.manager@example.test", firstName: "Nikos", lastName: "Georgiou" },
];
export const TRAVELERS = [
  // The two accounts of the recommendation comparison: different trips, so different suggestions.
  { email: "anna.culture@example.test", firstName: "Anna", lastName: "Culture", books: "Florence art and food" },
  { email: "ben.beach@example.test", firstName: "Ben", lastName: "Beach", books: "Rhodes beach week" },
];
const DESTINATIONS = [["Florence", "Italy"], ["Rome", "Italy"], ["Rhodes", "Greece"], ["Crete", "Greece"],
  ["Interlaken", "Switzerland"], ["Lisbon", "Portugal"]];

/** Travels start 40 + offset days from today, so they are always bookable. */
const TRAVELS = [
  { manager: 0, name: "Florence art and food", place: "Florence", offset: 0, days: 5, price: 690, activity: ["Uffizi gallery tour", "CULTURE"],
    food: ["Tuscan cooking class", "FOOD"], stay: ["Hotel Duomo", "HOTEL"], transport: "TRAIN" },
  { manager: 0, name: "Rome museums by rail", place: "Rome", offset: 10, days: 4, price: 540, activity: ["Vatican museums", "CULTURE"],
    food: ["Trastevere food walk", "FOOD"], stay: ["Hotel Navona", "HOTEL"], transport: "TRAIN" },
  { manager: 1, name: "Rhodes beach week", place: "Rhodes", offset: 5, days: 7, price: 820, activity: ["Lindos beach day", "BEACH"],
    food: ["Snorkelling trip", "SPORT"], stay: ["Aegean resort", "RESORT"], transport: "PLANE" },
  { manager: 1, name: "Crete sun and sea", place: "Crete", offset: 15, days: 6, price: 760, activity: ["Elafonisi beach", "BEACH"],
    food: ["Sea kayaking", "ADVENTURE"], stay: ["Chania resort", "RESORT"], transport: "PLANE" },
  { manager: 1, name: "Swiss Alps hiking", place: "Interlaken", offset: 20, days: 5, price: 980, activity: ["Jungfrau hike", "NATURE"],
    food: ["Paragliding", "ADVENTURE"], stay: ["Mountain hostel", "HOSTEL"], transport: "TRAIN" },
  { manager: 0, name: "Lisbon city lights", place: "Lisbon", offset: 25, days: 4, price: 480, activity: ["Alfama night walk", "NIGHTLIFE"],
    food: ["Pasteis tasting", "FOOD"], stay: ["Baixa apartment", "APARTMENT"], transport: "PLANE" },
];

async function call(method, path, { token, body, ok = [200, 201, 204] } = {}) {
  const response = await fetch(baseUrl + path, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(method === "POST" || method === "DELETE" ? { "Idempotency-Key": randomUUID() } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!ok.includes(response.status)) throw new Error(`${method} ${path} answered ${response.status}: ${text.slice(0, 200)}`);
  return { status: response.status, data };
}

const login = async (email, secret) => (await call("POST", "/api/auth/login", { body: { username: email, password: secret } })).data.accessToken;
const date = days => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);

async function all(path, token) {
  const items = [];
  for (let page = 0; page < 50; page++) {
    const { data } = await call("GET", `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=100`, { token });
    items.push(...data.items);
    if (items.length >= (data.total ?? data.totalElements ?? 0) || data.items.length === 0) return items;
  }
  return items;
}

async function main() {
  const admin = await login(process.env.ADMIN_EMAIL, process.env.ADMIN_PASSWORD);
  const users = await all("/api/users", admin);

  const managerIds = [];
  for (const manager of MANAGERS) {
    let user = users.find(existing => existing.email === manager.email);
    if (!user) {
      const created = (await call("POST", "/api/users", { token: admin, body: { ...manager, role: "TRAVEL_MANAGER" } })).data;
      await call("POST", "/api/password-setup", { body: { token: created.passwordSetup.token, password } });
      user = created;
      console.log(`Created manager ${manager.email}`);
    }
    managerIds.push(user.id);
  }
  for (const traveler of TRAVELERS) {
    const registered = await call("POST", "/api/registrations", { body: { email: traveler.email, firstName: traveler.firstName,
      lastName: traveler.lastName, password }, ok: [200, 201, 409] });
    if (registered.status !== 409) console.log(`Registered traveler ${traveler.email}`);
  }

  const destinations = await all("/api/destinations", admin);
  for (const [name, country] of DESTINATIONS) {
    if (!destinations.some(found => found.name === name && found.country === country)) {
      destinations.push((await call("POST", "/api/destinations", { token: admin, body: { name, country } })).data);
    }
  }

  const travelIds = {};
  for (const [index, managerEmail] of MANAGERS.map(manager => manager.email).entries()) {
    const token = await login(managerEmail, password);
    const own = await all("/api/travels", token);
    for (const travel of TRAVELS.filter(item => item.manager === index)) {
      let found = own.find(existing => existing.name === travel.name);
      if (!found) {
        const start = date(40 + travel.offset);
        const end = date(40 + travel.offset + travel.days - 1);
        const destination = destinations.find(item => item.name === travel.place);
        found = (await call("POST", "/api/travels", { token, body: {
          name: travel.name, description: `${travel.name}: a small-group trip with ${travel.activity[0].toLowerCase()} and ${travel.food[0].toLowerCase()}.`,
          startDate: start, endDate: end, capacity: 12, price: travel.price, currency: "EUR", timeZone: "Europe/Athens",
          stops: [{ destinationId: destination.id, position: 0, arrivalDate: start, departureDate: end }],
          activities: [{ name: travel.activity[0], description: "", date: start, category: travel.activity[1] },
            { name: travel.food[0], description: "", date: end, category: travel.food[1] }],
          accommodations: [{ name: travel.stay[0], address: `${travel.place} centre`, checkIn: start, checkOut: end, category: travel.stay[1] }],
          transports: [{ mode: travel.transport, departure: "Athens", arrival: travel.place, date: start }],
        } })).data;
        console.log(`Created travel ${travel.name}`);
      }
      if (found.status !== "PUBLISHED") await call("POST", `/api/travels/${found.id}/publication`, { token });
      travelIds[travel.name] = found.id;
    }
  }

  for (const traveler of TRAVELERS) {
    const token = await login(traveler.email, password);
    const travelId = travelIds[traveler.books];
    const mine = await all("/api/subscriptions", token);
    let subscription = mine.find(item => item.travelId === travelId && ["PENDING_PAYMENT", "CONFIRMED"].includes(item.status));
    if (!subscription) {
      subscription = (await call("POST", "/api/subscriptions", { token, body: { travelId } })).data;
      console.log(`${traveler.email} reserved ${traveler.books}`);
    }
    if (subscription.status === "PENDING_PAYMENT") {
      const paid = await call("POST", "/api/subscription-payments", { token, body: { subscriptionId: subscription.subscriptionId,
        testOutcome: "APPROVE" }, ok: [200, 201, 202, 400, 409, 503] });
      console.log(paid.status < 300 ? `${traveler.email} paid (${paid.data.state})`
        : `${traveler.email}: payment not possible here (HTTP ${paid.status}); enable PAYMENT_TEST_PROVIDER_ENABLED`);
    }
  }
  console.log("Demo data is ready. Sign in with the addresses above and DEMO_PASSWORD.");
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
