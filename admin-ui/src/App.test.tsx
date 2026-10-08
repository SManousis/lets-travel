import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import App from "./App";

function json(data: unknown): Response {
  return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
}

const adminMe = { userId: "a1", email: null, firstName: null, lastName: null, role: "ADMIN", status: "ACTIVE", memberSince: null };
const travelerMe = { userId: "t1", email: "ada@example.com", firstName: "Ada", lastName: "Lovelace",
  role: "TRAVELER", status: "ACTIVE", memberSince: "2026-10-01T00:00:00Z" };
const emptyContractPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
const adminDashboard = { incomeByMonth: [{ month: "2026-10", amounts: [{ amountMinor: 30000, currency: "EUR" }] }],
  organizedTravels: 2, activeTravelers: 7, averageRating: { average: 4.5, count: 2 }, openReports: 1,
  topManagers: [], topTravels: [], asOf: "2026-10-08T10:00:00Z" };

/** Answers for the screens an admin lands on. */
function adminRoutes(input: RequestInfo | URL): Response | null {
  const path = String(input);
  if (path === "/api/me") return json(adminMe);
  if (path.startsWith("/api/dashboards/admin?")) return json(adminDashboard);
  if (path === "/api/dashboards/admin/reconciliation") return json([]);
  return null;
}

function signedIn(token: string) {
  sessionStorage.setItem("travelPlanToken", token);
  sessionStorage.setItem("travelPlanTokenExpiresAt", String(Math.floor(Date.now() / 1000) + 300));
}

beforeEach(() => { sessionStorage.clear(); window.location.hash = ""; });
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("keeps the admin workspace behind sign-in and opens it after a valid login", async () => {
  const user = userEvent.setup();
  const expiresAt = Math.floor(Date.now() / 1000) + 300;
  let usersAuthorization = "";
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    if (String(input) === "/api/auth/login") {
      expect(options?.method).toBe("POST");
      expect(JSON.parse(String(options?.body))).toEqual({ username: "admin", password: "secret" });
      return json({ accessToken: "admin-token", expiresAt });
    }
    const known = adminRoutes(input);
    if (known) return known;
    if (String(input) === "/api/users?page=0&size=10") {
      usersAuthorization = new Headers(options?.headers).get("Authorization") ?? "";
      return json({ items: [], page: 0, size: 10, total: 0 });
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  }));

  render(<App />);
  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  expect(screen.queryByRole("navigation", { name: "Main navigation" })).not.toBeInTheDocument();
  await user.type(screen.getByLabelText("Username"), "admin");
  await user.type(screen.getByLabelText("Password"), "secret");
  await user.click(screen.getByRole("button", { name: "Sign in" }));

  expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
  expect(await screen.findByText("Active travelers")).toBeInTheDocument();
  const nav = screen.getByRole("navigation", { name: "Main navigation" });
  expect(within(nav).getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
  await user.click(within(nav).getByRole("link", { name: "Users" }));
  await screen.findByText("No users found.");
  expect(usersAuthorization).toBe("Bearer admin-token");
  expect(sessionStorage.getItem("travelPlanToken")).toBe("admin-token");
});

it("shows an authentication error and leaves the workspace locked after rejected credentials", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 401 })));
  render(<App />);

  await user.type(screen.getByLabelText("Username"), "admin");
  await user.type(screen.getByLabelText("Password"), "wrong-password");
  await user.click(screen.getByRole("button", { name: "Sign in" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password.");
  expect(screen.queryByRole("navigation", { name: "Main navigation" })).not.toBeInTheDocument();
  expect(sessionStorage.getItem("travelPlanToken")).toBeNull();
});

it("removes a stored session when its expiration time arrives", async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-01T10:00:00Z"));
  sessionStorage.setItem("travelPlanToken", "admin-token");
  sessionStorage.setItem("travelPlanTokenExpiresAt", String(Math.floor(Date.now() / 1000) + 1));
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => adminRoutes(input) ?? json({ items: [] })));

  render(<App />);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(screen.getByRole("navigation", { name: "Main navigation" })).toBeInTheDocument();
  act(() => vi.advanceTimersByTime(1_000));

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  expect(sessionStorage.getItem("travelPlanToken")).toBeNull();
  expect(sessionStorage.getItem("travelPlanTokenExpiresAt")).toBeNull();
});

it("returns to sign-in when a protected request receives 401", async () => {
  sessionStorage.setItem("travelPlanToken", "admin-token");
  sessionStorage.setItem("travelPlanTokenExpiresAt", String(Math.floor(Date.now() / 1000) + 300));
  vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 401 })));

  render(<App />);
  expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  expect(sessionStorage.getItem("travelPlanToken")).toBeNull();
  expect(screen.queryByRole("navigation", { name: "Main navigation" })).not.toBeInTheDocument();
});

function travelerFetch(asked: string[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    asked.push(path);
    if (path === "/api/me") return json(travelerMe);
    if (path.startsWith("/api/recommendations")) return json({ strategy: "POPULAR", items: [] });
    if (path.startsWith("/api/travels?")) return json({ items: [], page: 0, size: 50, total: 0 });
    if (path === "/api/subscription-payments") return json([]);
    if (path.startsWith("/api/subscriptions?") || path.startsWith("/api/feedback/mine?") || path.startsWith("/api/reports?")) {
      return json(emptyContractPage);
    }
    throw new Error(`Unexpected request: ${path}`);
  });
}

it("shows travelers their own home and navigation, without admin or manager screens", async () => {
  const user = userEvent.setup();
  signedIn("traveler-token");
  vi.stubGlobal("fetch", travelerFetch([]));

  render(<App />);
  expect(await screen.findByRole("heading", { name: "Your account" })).toBeInTheDocument();
  expect(screen.getByText("Ada Lovelace")).toBeInTheDocument();
  expect(await screen.findByRole("heading", { name: "Popular upcoming travels" })).toBeInTheDocument();
  const nav = screen.getByRole("navigation", { name: "Main navigation" });
  expect(within(nav).getAllByRole("link").map(link => link.textContent))
    .toEqual(["Home", "Find and book", "My feedback", "My reports", "My statistics"]);

  await user.click(within(nav).getByRole("link", { name: "Find and book" }));
  expect(await screen.findByText("You have no subscriptions yet.")).toBeInTheDocument();
  expect(screen.getByRole("main")).toHaveFocus();
  await user.click(within(nav).getByRole("link", { name: "My feedback" }));
  expect(await screen.findByText("No finished trips are waiting for your feedback.")).toBeInTheDocument();
  await user.click(within(nav).getByRole("link", { name: "My reports" }));
  expect(await screen.findByText("You have not filed any reports.")).toBeInTheDocument();
});

it("guards screens the role may not open, without asking the server for their data", async () => {
  signedIn("traveler-token");
  const asked: string[] = [];
  vi.stubGlobal("fetch", travelerFetch(asked));
  window.location.hash = "#/users";

  render(<App />);
  expect(await screen.findByRole("heading", { name: "Not available for your role" })).toBeInTheDocument();
  expect(screen.getByText("Users is not part of the traveler workspace.")).toBeInTheDocument();
  expect(asked).toEqual(["/api/me"]);

  act(() => { window.location.hash = "#/no-such-page"; window.dispatchEvent(new HashChangeEvent("hashchange")); });
  expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Go to Home" })).toHaveAttribute("href", "#/home");
});

it("gives travel managers their listings, subscribers and analytics as well as traveler screens", async () => {
  signedIn("manager-token");
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === "/api/me") return json({ ...travelerMe, role: "TRAVEL_MANAGER" });
    if (path.startsWith("/api/recommendations")) return json({ strategy: "POPULAR", items: [] });
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<App />);
  const nav = await screen.findByRole("navigation", { name: "Main navigation" });
  expect(within(nav).getAllByRole("link").map(link => link.textContent)).toEqual(["Home", "Find and book", "Travels",
    "Subscribers", "Travel feedback", "Analytics", "My feedback", "My reports", "My statistics"]);
});

it("lets a visitor sign up and then sign in with the new email", async () => {
  const user = userEvent.setup();
  let registration: unknown;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    if (String(input) === "/api/registrations") {
      registration = JSON.parse(String(options?.body));
      return new Response(JSON.stringify({ userId: "n1" }), { status: 201 });
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  }));
  window.location.hash = "#/register";

  render(<App />);
  await user.type(screen.getByLabelText("Email"), "new@example.com");
  await user.type(screen.getByLabelText("First name"), "New");
  await user.type(screen.getByLabelText("Last name"), "Traveler");
  await user.type(screen.getByLabelText("Password"), "Chosen-Secret-1");
  await user.click(screen.getByRole("button", { name: "Create account" }));

  expect(await screen.findByRole("status")).toHaveTextContent("Account created.");
  expect(registration).toEqual({ email: "new@example.com", firstName: "New", lastName: "Traveler", password: "Chosen-Secret-1" });
  expect(screen.getByLabelText("Username")).toHaveValue("new@example.com");
});

it("sets a password from a one-time link and checks the repeat", async () => {
  const user = userEvent.setup();
  let setup: unknown;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    if (String(input) === "/api/password-setup") {
      setup = JSON.parse(String(options?.body));
      return new Response(null, { status: 204 });
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  }));
  window.location.hash = "#/set-password?token=abc123";

  render(<App />);
  await user.type(screen.getByLabelText("New password"), "Their-Own-Secret");
  await user.type(screen.getByLabelText("Repeat password"), "Something-Else-1");
  await user.click(screen.getByRole("button", { name: "Set password" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("The passwords do not match.");
  expect(setup).toBeUndefined();

  await user.clear(screen.getByLabelText("Repeat password"));
  await user.type(screen.getByLabelText("Repeat password"), "Their-Own-Secret");
  await user.click(screen.getByRole("button", { name: "Set password" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Password set.");
  expect(setup).toEqual({ token: "abc123", password: "Their-Own-Secret" });
});
