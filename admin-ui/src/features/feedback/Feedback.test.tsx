import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FeedbackPanel } from "./FeedbackPanel";
import { FeedbackOverview } from "./FeedbackOverview";
import { ManagerProfile } from "../managers/ManagerProfile";
import { mayLeaveFeedback, stars } from "./format";
import type { Feedback, ManagerPage, Subscription } from "../../lib/types";

const context = { token: "test-token", onUnauthorized: () => {} };
const XSS = "<script>window.hacked = true</script><img src=x onerror=\"window.hacked = true\">";

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

function page<T>(items: T[]) {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 };
}

function trip(id: string, overrides: Partial<Subscription> = {}): Subscription {
  return {
    subscriptionId: `sub-${id}`, travelId: `travel-${id}`, travelTitle: `Trip ${id}`, travelerId: "me", managerId: "m1",
    status: "COMPLETED", price: { amountMinor: 10000, currency: "EUR" }, startDate: "2026-09-01", endDate: "2026-09-05",
    timeZone: "UTC", cancellationCutoff: "2026-08-29", travelerMayCancel: false, paymentId: "p1",
    reservationExpiresAt: null, cancellationReason: null, cancelledAt: null,
    createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z", ...overrides,
  };
}

function feedback(overrides: Partial<Feedback> = {}): Feedback {
  return {
    feedbackId: "f1", travelId: "travel-x", travelTitle: "Crete", managerId: "m1", authorDisplayName: "Ada Lovelace",
    rating: 4, comment: "Lovely", createdAt: "2026-09-10T10:00:00Z", ...overrides,
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete (window as { hacked?: boolean }).hacked;
});

it("offers feedback only for finished trips", () => {
  expect(mayLeaveFeedback(trip("a"), "2026-10-07")).toBe(true);
  expect(mayLeaveFeedback(trip("b", { status: "CONFIRMED", endDate: "2026-10-06" }), "2026-10-07")).toBe(true);
  expect(mayLeaveFeedback(trip("c", { status: "CONFIRMED", endDate: "2026-10-07" }), "2026-10-07")).toBe(false);
  expect(mayLeaveFeedback(trip("d", { status: "CANCELLED_BY_TRAVELER" }), "2026-10-07")).toBe(false);
  expect(stars(4)).toBe("★★★★☆");
});

it("lets a traveler rate an eligible trip once, keeping one idempotency key across retries", async () => {
  const user = userEvent.setup();
  const given: Feedback[] = [feedback({ travelId: "travel-rated", travelTitle: "Already rated" })];
  const posts: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/subscriptions?")) return json(page([
      trip("done"), trip("rated", { travelId: "travel-rated" }),
      trip("future", { status: "CONFIRMED", startDate: "2099-01-01", endDate: "2099-01-05" }),
      trip("cancelled", { status: "CANCELLED_BY_TRAVELER" }),
    ]));
    if (path.startsWith("/api/feedback/mine?")) return json(page(given));
    if (path === "/api/feedback" && options?.method === "POST") {
      posts.push({ key: new Headers(options.headers).get("Idempotency-Key"), body: JSON.parse(String(options.body)) });
      if (posts.length === 1) return json({ code: "DEPENDENCY_UNAVAILABLE", message: "down" }, 503);
      given.push(feedback({ feedbackId: "f2", travelId: "travel-done", travelTitle: "Trip done", rating: 3, comment: "Good food" }));
      return json(given[1], 201);
    }
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<FeedbackPanel context={context} />);
  const offered = await screen.findAllByRole("button", { name: "Leave feedback" });
  expect(offered).toHaveLength(1);
  expect(screen.getByText("Trip done")).toBeInTheDocument();
  expect(screen.queryByText("Trip future")).not.toBeInTheDocument();
  expect(screen.queryByText("Trip cancelled")).not.toBeInTheDocument();

  await user.click(offered[0]);
  await user.selectOptions(screen.getByLabelText("Rating"), "3");
  await user.type(screen.getByLabelText(/Comment/), "Good food");
  await user.click(screen.getByRole("button", { name: "Send feedback" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  await user.click(screen.getByRole("button", { name: "Send feedback" }));
  expect(await screen.findByText(/Your feedback on Trip done was sent/)).toBeInTheDocument();

  expect(posts).toHaveLength(2);
  expect(posts[1].key).toBe(posts[0].key);
  expect(posts[1].body).toEqual({ travelId: "travel-done", rating: 3, comment: "Good food" });
  expect(screen.getByText("No finished trips are waiting for your feedback.")).toBeInTheDocument();
});

it("shows managers their feedback and renders a script comment as plain text", async () => {
  const user = userEvent.setup();
  const requested: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    requested.push(path);
    if (path.startsWith("/api/travels?")) return json({ items: [{ id: "travel-x", name: "Crete", startDate: "2026-09-01" }], page: 0, size: 100, total: 1 });
    if (path.startsWith("/api/feedback?")) return json(page([feedback({ comment: XSS })]));
    throw new Error(`Unexpected request: ${path}`);
  }));

  const { container } = render(<FeedbackOverview context={context} />);
  expect(await screen.findByText(XSS)).toBeInTheDocument();
  expect(container.querySelector("script")).toBeNull();
  expect(container.querySelector("img")).toBeNull();
  expect((window as { hacked?: boolean }).hacked).toBeUndefined();
  expect(screen.getByText(/Ada Lovelace/)).toBeInTheDocument();

  await user.selectOptions(await screen.findByLabelText("Travel"), "travel-x");
  expect(requested.at(-1)).toBe("/api/feedback?travelId=travel-x&page=0&size=20");
});

it("shows the manager page with ratings and the report count but no rating authors", async () => {
  const manager: ManagerPage = {
    managerId: "m1", displayName: "Maria Manager", memberSince: "2025-03-04T12:30:00Z", completedTravels: 7,
    totalTravels: 11, averageRating: 4.3, ratingCount: 3, reportCount: 2,
    recentRatings: [{ travelTitle: "Naxos", rating: 5, comment: XSS, createdAt: "2026-07-01T10:00:00Z" }],
  };
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/managers/m1") return json(manager);
    throw new Error(`Unexpected request: ${String(input)}`);
  }));

  const { container } = render(<ManagerProfile context={context} managerId="m1" />);
  const pageView = within(await screen.findByLabelText("Travel manager page"));
  expect(pageView.getByText("Maria Manager")).toBeInTheDocument();
  expect(pageView.getByText("7 of 11")).toBeInTheDocument();
  expect(pageView.getByText("4.3 of 5 (3 ratings)")).toBeInTheDocument();
  expect(pageView.getByText("Upheld reports").nextElementSibling).toHaveTextContent("2");
  expect(pageView.getByText(XSS)).toBeInTheDocument();
  expect(container.querySelector("script")).toBeNull();
  expect(pageView.getByRole("button", { name: "Report this manager" })).toBeInTheDocument();
});
