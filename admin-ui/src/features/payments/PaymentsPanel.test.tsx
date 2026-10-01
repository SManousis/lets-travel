import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PaymentsPanel } from "./PaymentsPanel";
import type { PaymentMethod } from "../../lib/types";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function json(data: unknown): Response {
  return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
}

it("shows the local default and lets an admin select another active method", async () => {
  const methods: PaymentMethod[] = [
    { id: "method-one", userId: "user-one", gatewayId: "gateway-one", provider: "STRIPE",
      displayLabel: "Primary card", isDefault: true, status: "ACTIVE", displayMetadata: "Visa 1111",
      failureCode: null, createdAt: "2026-10-01T00:00:00Z" },
    { id: "method-two", userId: "user-one", gatewayId: "gateway-one", provider: "STRIPE",
      displayLabel: "Backup card", isDefault: false, status: "ACTIVE", displayMetadata: "Visa 2222",
      failureCode: null, createdAt: "2026-10-01T00:00:01Z" },
  ];
  const patches: unknown[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/payment-methods" && options?.method === "PATCH") throw new Error("Unexpected patch path");
    if (path === "/api/payment-methods/method-two" && options?.method === "PATCH") {
      patches.push(JSON.parse(String(options.body)));
      methods[0].isDefault = false;
      methods[1].isDefault = true;
      return json(methods[1]);
    }
    if (path === "/api/payment-methods") return json(methods.map(method => ({ ...method })));
    if (path === "/api/payment-gateways") return json([{
      id: "gateway-one", provider: "STRIPE", displayName: "Sandbox", enabled: true,
      createdAt: "2026-10-01T00:00:00Z",
    }]);
    if (path === "/api/users?page=0&size=100") return json({ items: [{
      id: "user-one", email: "user@example.com", firstName: "User", lastName: "One",
      role: "OPERATOR", status: "ACTIVE", createdAt: "2026-10-01T00:00:00Z",
    }], page: 0, size: 100, total: 1 });
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<PaymentsPanel context={{ token: "test-token", onUnauthorized: () => {} }} active />);
  const primaryRow = (await screen.findByText("Primary card")).closest("li");
  const backupRow = screen.getByText("Backup card").closest("li");
  if (!primaryRow || !backupRow) throw new Error("Payment method rows missing");
  expect(within(primaryRow).getByText("Default")).toBeVisible();
  await userEvent.setup().click(within(backupRow).getByRole("button", { name: "Make default" }));
  await waitFor(() => expect(patches).toEqual([{ isDefault: true }]));
  await waitFor(() => expect(within(backupRow).getByText("Default")).toBeVisible());
  expect(within(primaryRow).queryByText("Default")).not.toBeInTheDocument();
});

it("sends create-time default preference with a sandbox method", async () => {
  const posts: Record<string, unknown>[] = [];
  vi.stubGlobal("crypto", { randomUUID: () => "00000000-0000-4000-8000-000000000001" });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/payment-methods" && options?.method === "POST") {
      posts.push(JSON.parse(String(options.body)) as Record<string, unknown>);
      return json({ ...posts[0], id: "method-one", status: "ACTIVE", isDefault: true });
    }
    if (path === "/api/payment-methods") return json([]);
    if (path === "/api/payment-gateways") return json([{
      id: "gateway-one", provider: "STRIPE", displayName: "Sandbox", enabled: true,
      createdAt: "2026-10-01T00:00:00Z",
    }]);
    if (path === "/api/users?page=0&size=100") return json({ items: [{
      id: "user-one", email: "user@example.com", firstName: "User", lastName: "One",
      role: "OPERATOR", status: "ACTIVE", createdAt: "2026-10-01T00:00:00Z",
    }], page: 0, size: 100, total: 1 });
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<PaymentsPanel context={{ token: "test-token", onUnauthorized: () => {} }} active />);
  await screen.findByText("No payment methods found.");
  const formSection = screen.getByRole("heading", { name: "Connect sandbox method" }).closest("section");
  if (!formSection) throw new Error("Payment method form missing");
  const form = within(formSection);
  const user = userEvent.setup();
  await user.selectOptions(form.getByLabelText("User"), "user-one");
  await user.selectOptions(form.getByLabelText("Gateway"), "gateway-one");
  await user.type(form.getByLabelText("Provider reference"), "pm_test_1");
  await user.type(form.getByLabelText("Display label"), "Travel card");
  await user.click(form.getByLabelText("Set as default after connection"));
  await user.click(form.getByRole("button", { name: "Connect method" }));
  await waitFor(() => expect(posts).toHaveLength(1));
  expect(posts[0]).toMatchObject({ userId: "user-one", gatewayId: "gateway-one",
    providerReference: "pm_test_1", displayLabel: "Travel card", isDefault: true });
});

it("keeps a pending connection's inputs fixed while retrying its key", async () => {
  const keys: (string | null)[] = [];
  vi.stubGlobal("crypto", { randomUUID: () => "00000000-0000-4000-8000-000000000001" });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/payment-methods" && options?.method === "POST") {
      keys.push(new Headers(options.headers).get("Idempotency-Key"));
      return new Response(JSON.stringify({ id: "method-one", status: "PENDING" }), {
        status: 202, headers: { "Content-Type": "application/json" },
      });
    }
    if (path === "/api/payment-methods") return json([]);
    if (path === "/api/payment-gateways") return json([{
      id: "gateway-one", provider: "STRIPE", displayName: "Sandbox", enabled: true,
      createdAt: "2026-10-01T00:00:00Z",
    }]);
    if (path === "/api/users?page=0&size=100") return json({ items: [{
      id: "user-one", email: "user@example.com", firstName: "User", lastName: "One",
      role: "OPERATOR", status: "ACTIVE", createdAt: "2026-10-01T00:00:00Z",
    }], page: 0, size: 100, total: 1 });
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<PaymentsPanel context={{ token: "test-token", onUnauthorized: () => {} }} active />);
  await screen.findByText("No payment methods found.");
  const formSection = screen.getByRole("heading", { name: "Connect sandbox method" }).closest("section");
  if (!formSection) throw new Error("Payment method form missing");
  const form = within(formSection);
  const user = userEvent.setup();
  await user.selectOptions(form.getByLabelText("User"), "user-one");
  await user.selectOptions(form.getByLabelText("Gateway"), "gateway-one");
  await user.type(form.getByLabelText("Provider reference"), "pm_test_1");
  await user.type(form.getByLabelText("Display label"), "Travel card");
  await user.click(form.getByLabelText("Set as default after connection"));
  await user.click(form.getByRole("button", { name: "Connect method" }));
  await screen.findByText(/Provider inspection is pending/);
  for (const name of ["User", "Gateway", "Provider reference", "Display label", "Set as default after connection"]) {
    expect(form.getByLabelText(name)).toBeDisabled();
  }
  await user.click(form.getByRole("button", { name: "Connect method" }));
  await waitFor(() => expect(keys).toHaveLength(2));
  expect(keys[0]).toBe(keys[1]);
});
