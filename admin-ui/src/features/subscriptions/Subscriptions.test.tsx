import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TravelerTrips } from "./TravelerTrips";
import { SubscriberManager } from "./SubscriberManager";
import { cutoffDate, formatMoney } from "./format";
import type { Subscriber, Subscription, TravelOffer } from "../../lib/types";

const context = { token: "test-token", onUnauthorized: () => {} };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

function page<T>(items: T[]) {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 };
}

const offer: TravelOffer = {
  id: "travel-1", managerId: "manager-1", name: "Crete in spring", description: "A week on the island.",
  startDate: "2026-11-20", endDate: "2026-11-26", durationDays: 7, capacity: 10, price: 450.5, currency: "EUR",
  status: "PUBLISHED", participantCount: 3, remainingCapacity: 7, bookable: true, timeZone: "Europe/Athens",
};

function subscription(overrides: Partial<Subscription> = {}): Subscription {
  return {
    subscriptionId: "sub-1", travelId: "travel-1", travelTitle: "Crete in spring", travelerId: "me", managerId: "manager-1",
    status: "PENDING_PAYMENT", price: { amountMinor: 45050, currency: "EUR" }, startDate: "2026-11-20",
    endDate: "2026-11-26", timeZone: "Europe/Athens", cancellationCutoff: "2026-11-17", travelerMayCancel: true,
    paymentId: null, reservationExpiresAt: null, cancellationReason: null, cancelledAt: null,
    createdAt: "2026-10-07T09:00:00Z", updatedAt: "2026-10-07T09:00:00Z", ...overrides,
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("computes the cancellation cutoff and formats minor units", () => {
  expect(cutoffDate("2026-11-20")).toBe("2026-11-17");
  expect(cutoffDate("2026-03-02")).toBe("2026-02-27");
  expect(formatMoney(45050, "EUR")).toMatch(/450[.,]50/);
  expect(formatMoney(1200, "JPY")).toMatch(/1[,.]?200/);
});

it("subscribes with one idempotency key across retries and shows the cutoff", async () => {
  const user = userEvent.setup();
  const mine: Subscription[] = [];
  const posts: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/travels?")) return json({ items: [offer], page: 0, size: 50, total: 1 });
    if (path.startsWith("/api/subscriptions?")) return json(page(mine));
    if (path === "/api/subscription-payments") return json([]);
    if (path === "/api/subscriptions" && options?.method === "POST") {
      posts.push({ key: new Headers(options.headers).get("Idempotency-Key"), body: JSON.parse(String(options.body)) });
      if (posts.length === 1) throw new Error("Network timeout");
      mine.push(subscription());
      return json(subscription(), 201);
    }
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<TravelerTrips context={context} />);
  await user.click(await screen.findByRole("button", { name: /Crete in spring/ }));
  expect(screen.getByText("Until 2026-11-17 (Europe/Athens time)")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Subscribe" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("could not be reached");
  await user.click(screen.getByRole("button", { name: "Subscribe" }));
  await screen.findByText(/Your place on Crete in spring is reserved/);

  expect(posts).toHaveLength(2);
  expect(posts[0].key).toMatch(/^[0-9a-f-]{36}$/);
  expect(posts[1].key).toBe(posts[0].key);
  expect(posts[1].body).toEqual({ travelId: "travel-1" });
  const list = within(screen.getByRole("heading", { name: "My subscriptions" }).closest("section") as HTMLElement);
  expect(list.getByText("Awaiting payment")).toBeInTheDocument();
  expect(list.getByText("Free cancellation until 2026-11-17 (Europe/Athens time), full refund if paid")).toBeInTheDocument();
  expect(list.getByRole("button", { name: "Pay now" })).toBeInTheDocument();
});

it("cancels before the cutoff and explains a closed cancellation", async () => {
  const user = userEvent.setup();
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const mine = [subscription(), subscription({
    subscriptionId: "sub-2", travelId: "travel-2", travelTitle: "Lisbon weekend", status: "CONFIRMED",
    travelerMayCancel: false, cancellationCutoff: "2026-10-05",
  }), subscription({
    subscriptionId: "sub-3", travelId: "travel-3", travelTitle: "Rome", status: "CANCELLED_BY_MANAGER",
    travelerMayCancel: false, cancellationReason: "Trip rescheduled",
  })];
  const cancellations: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/travels?")) return json({ items: [], page: 0, size: 50, total: 0 });
    if (path.startsWith("/api/subscriptions?")) return json(page(mine));
    if (path === "/api/subscription-payments") return json([]);
    if (path.endsWith("/cancellation") && options?.method === "POST") {
      expect(new Headers(options.headers).get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
      cancellations.push(path);
      mine[0] = subscription({ status: "CANCELLED_BY_TRAVELER", travelerMayCancel: false });
      return json(mine[0]);
    }
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<TravelerTrips context={context} />);
  expect(await screen.findByText("Cancellation closed on 2026-10-05 (3 days before the start)")).toBeInTheDocument();
  expect(screen.getByText("Reason: Trip rescheduled")).toBeInTheDocument();
  expect(screen.getAllByRole("button", { name: "Cancel" })).toHaveLength(1);

  await user.click(screen.getByRole("button", { name: "Cancel" }));
  await screen.findByText("Your subscription to Crete in spring is cancelled.");
  expect(cancellations).toEqual(["/api/subscriptions/sub-1/cancellation"]);
  expect(screen.getByText("Cancelled by you")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
});

it("lets a manager view a subscriber profile and unsubscribe with a reason", async () => {
  const user = userEvent.setup();
  const subscribers: Subscriber[] = [{
    subscriptionId: "sub-1", travelerId: "traveler-1", displayName: "Ada Lovelace", memberSince: "2025-01-02",
    completedTrips: 4, reportCount: 0, status: "CONFIRMED", subscribedAt: "2026-10-07T09:00:00Z",
    cancellationReason: null, cancelledAt: null,
  }];
  const removals: { path: string; key: string | null; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/travels?")) return json({ items: [offer], page: 0, size: 100, total: 1 });
    if (path.startsWith("/api/managed-travels/travel-1/subscribers?")) return json(page(subscribers));
    if (path.endsWith("/removal") && options?.method === "POST") {
      removals.push({ path, key: new Headers(options.headers).get("Idempotency-Key"), body: JSON.parse(String(options.body)) });
      subscribers[0] = { ...subscribers[0], status: "CANCELLED_BY_MANAGER", cancellationReason: "Missing documents" };
      return json({});
    }
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<SubscriberManager context={context} />);
  await user.selectOptions(await screen.findByLabelText("Travel"), "travel-1");
  await user.click(await screen.findByRole("button", { name: "View profile" }));
  const profile = within(screen.getByLabelText("Traveler profile"));
  expect(profile.getByText("2025-01-02")).toBeInTheDocument();
  expect(profile.getByText("4")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Unsubscribe" }));
  const confirm = screen.getByRole("button", { name: "Confirm unsubscribe" });
  expect(confirm).toBeDisabled();
  await user.type(screen.getByLabelText("Reason"), "Missing documents");
  await user.click(confirm);
  await screen.findByText("Ada Lovelace was unsubscribed.");
  expect(removals).toEqual([{ path: "/api/managed-travels/travel-1/subscribers/sub-1/removal",
    key: expect.stringMatching(/^[0-9a-f-]{36}$/), body: { reason: "Missing documents" } }]);
  await waitFor(() => expect(screen.getByText("Removed by the organiser")).toBeInTheDocument());
  expect(screen.queryByRole("button", { name: "Unsubscribe" })).not.toBeInTheDocument();
});
