import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import App from "./App";

function json(data: unknown): Response {
  return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
}

const adminMe = { userId: "a1", email: null, firstName: null, lastName: null, role: "ADMIN", status: "ACTIVE", memberSince: null };

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
    if (String(input) === "/api/me") return json(adminMe);
    if (String(input) === "/api/users?page=0&size=10") {
      usersAuthorization = new Headers(options?.headers).get("Authorization") ?? "";
      return json({ items: [], page: 0, size: 10, total: 0 });
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  }));

  render(<App />);
  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  expect(screen.queryByRole("navigation", { name: "Admin sections" })).not.toBeInTheDocument();
  await user.type(screen.getByLabelText("Password"), "secret");
  await user.click(screen.getByRole("button", { name: "Sign in" }));

  expect(await screen.findByRole("navigation", { name: "Admin sections" })).toBeInTheDocument();
  await screen.findByText("No users found.");
  expect(usersAuthorization).toBe("Bearer admin-token");
  expect(sessionStorage.getItem("travelPlanToken")).toBe("admin-token");
});

it("shows an authentication error and leaves the workspace locked after rejected credentials", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 401 })));
  render(<App />);

  await user.type(screen.getByLabelText("Password"), "wrong-password");
  await user.click(screen.getByRole("button", { name: "Sign in" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password.");
  expect(screen.queryByRole("navigation", { name: "Admin sections" })).not.toBeInTheDocument();
  expect(sessionStorage.getItem("travelPlanToken")).toBeNull();
});

it("removes a stored session when its expiration time arrives", async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-01T10:00:00Z"));
  sessionStorage.setItem("travelPlanToken", "admin-token");
  sessionStorage.setItem("travelPlanTokenExpiresAt", String(Math.floor(Date.now() / 1000) + 1));
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) =>
    String(input) === "/api/me" ? json(adminMe) : json({ items: [], page: 0, size: 10, total: 0 })));

  render(<App />);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(screen.getByRole("navigation", { name: "Admin sections" })).toBeInTheDocument();
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
  expect(screen.queryByRole("navigation", { name: "Admin sections" })).not.toBeInTheDocument();
});

it("shows travelers their account instead of the admin workspace", async () => {
  sessionStorage.setItem("travelPlanToken", "traveler-token");
  sessionStorage.setItem("travelPlanTokenExpiresAt", String(Math.floor(Date.now() / 1000) + 300));
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/me") {
      return json({ userId: "t1", email: "ada@example.com", firstName: "Ada", lastName: "Lovelace",
        role: "TRAVELER", status: "ACTIVE", memberSince: "2026-10-01T00:00:00Z" });
    }
    if (String(input).startsWith("/api/travels?")) return json({ items: [], page: 0, size: 50, total: 0 });
    if (String(input).startsWith("/api/subscriptions?") || String(input).startsWith("/api/feedback/mine?")
      || String(input).startsWith("/api/reports?")) {
      return json({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  }));

  render(<App />);
  expect(await screen.findByRole("heading", { name: "Your account" })).toBeInTheDocument();
  expect(screen.getByText("Ada Lovelace")).toBeInTheDocument();
  expect(screen.getAllByText("Traveler").length).toBeGreaterThan(0);
  expect(screen.queryByRole("navigation", { name: "Admin sections" })).not.toBeInTheDocument();
  expect(await screen.findByText("You have no subscriptions yet.")).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Subscribers" })).not.toBeInTheDocument();
  expect(await screen.findByText("No finished trips are waiting for your feedback.")).toBeInTheDocument();
  expect(await screen.findByText("You have not filed any reports.")).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Feedback on your travels" })).not.toBeInTheDocument();
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
