import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import App from "./App";

function json(data: unknown): Response {
  return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => sessionStorage.clear());
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

  expect(await screen.findByRole("alert")).toHaveTextContent("Invalid administrator credentials.");
  expect(screen.queryByRole("navigation", { name: "Admin sections" })).not.toBeInTheDocument();
  expect(sessionStorage.getItem("travelPlanToken")).toBeNull();
});

it("removes a stored session when its expiration time arrives", () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-01T10:00:00Z"));
  sessionStorage.setItem("travelPlanToken", "admin-token");
  sessionStorage.setItem("travelPlanTokenExpiresAt", String(Math.floor(Date.now() / 1000) + 1));
  vi.stubGlobal("fetch", vi.fn(async () => json({ items: [], page: 0, size: 10, total: 0 })));

  render(<App />);
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
