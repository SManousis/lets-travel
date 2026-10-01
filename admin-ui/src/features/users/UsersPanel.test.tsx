import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { UsersPanel } from "./UsersPanel";
import type { User } from "../../lib/types";

const createdAt = "2026-10-01T00:00:00Z";

function json(data: unknown): Response {
  return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => sessionStorage.clear());
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("reuses a user creation key after failure and rotates it when the form changes or succeeds", async () => {
  const user = userEvent.setup();
  let nextUuid = 0;
  vi.stubGlobal("crypto", {
    randomUUID: () => `00000000-0000-4000-8000-${String(++nextUuid).padStart(12, "0")}`,
  });
  const users: User[] = [];
  const posts: { key: string | null; body: Record<string, string> }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/users?page=0&size=10") {
      return json({ items: users, page: 0, size: 10, total: users.length });
    }
    if (path.startsWith("/api/travelers?")) {
      return json({ items: [], page: 0, size: 10, total: 0 });
    }
    if (path === "/api/users" && options?.method === "POST") {
      const body = JSON.parse(String(options.body)) as Record<string, string>;
      posts.push({ key: new Headers(options.headers).get("Idempotency-Key"), body });
      if (posts.length === 1 || posts.length === 2) throw new Error("Temporary outage");
      const created: User = {
        id: `user-${users.length + 1}`, email: body.email, firstName: body.firstName,
        lastName: body.lastName, role: body.role as User["role"], status: "ACTIVE", createdAt,
      };
      users.push(created);
      return json(created);
    }
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<UsersPanel context={{ token: "test-token", onUnauthorized: () => {} }} active />);
  await screen.findByText("No users found.");
  const createSection = screen.getByRole("heading", { name: "Create user" }).closest("section");
  if (!createSection) throw new Error("Create user form missing");
  const form = within(createSection);
  await user.type(form.getByLabelText("Email"), "jamie@example.com");
  await user.type(form.getByLabelText("First name"), "Jamie");
  await user.type(form.getByLabelText("Last name"), "Doe");

  await user.click(form.getByRole("button", { name: "Create user" }));
  await screen.findByRole("alert");
  expect(posts).toHaveLength(1);
  expect(posts[0].key).toMatch(/^[0-9a-f-]{36}$/);

  await user.clear(form.getByLabelText("First name"));
  await user.type(form.getByLabelText("First name"), "Janet");
  await user.click(form.getByRole("button", { name: "Create user" }));
  await waitFor(() => expect(posts).toHaveLength(2));
  await waitFor(() => expect(form.getByRole("button", { name: "Create user" })).toBeEnabled());

  await user.click(form.getByRole("button", { name: "Create user" }));
  await screen.findByText(/User created\. Arrange their initial credentials/);
  expect(posts).toHaveLength(3);
  expect(posts[1].key).not.toBe(posts[0].key);
  expect(posts[2].key).toBe(posts[1].key);
  expect(posts[2].body).toEqual({ email: "jamie@example.com", firstName: "Janet", lastName: "Doe", role: "OPERATOR" });
  expect(form.getByLabelText("Email")).toHaveValue("");

  await user.type(form.getByLabelText("Email"), "alex@example.com");
  await user.type(form.getByLabelText("First name"), "Alex");
  await user.type(form.getByLabelText("Last name"), "Smith");
  await user.click(form.getByRole("button", { name: "Create user" }));
  await waitFor(() => expect(posts).toHaveLength(4));
  expect(posts[3].key).not.toBe(posts[2].key);
  await screen.findByRole("heading", { name: "Alex Smith" });
});
