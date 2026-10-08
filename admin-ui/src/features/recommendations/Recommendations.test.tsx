import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { ApiContext } from "../../lib/api";
import { Recommendations, reasonText } from "./Recommendations";

const context: ApiContext = { token: "token", onUnauthorized: vi.fn() };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("shows personalized travels with the reasons from the traveler's own trips", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    expect(String(input)).toBe("/api/recommendations?limit=6");
    return json({ strategy: "PERSONALIZED", items: [{ travelId: "t1", title: "Florence art", startDate: "2026-11-17",
      endDate: "2026-11-21", price: { amountMinor: 65000, currency: "EUR" }, score: 0.65,
      reasons: [{ field: "ACTIVITY_CATEGORY", value: "CULTURE" }, { field: "TRANSPORT_MODE", value: "TRAIN" }] }] });
  }));
  render(<Recommendations context={context} />);

  expect(await screen.findByRole("heading", { name: "Recommended for you" })).toBeInTheDocument();
  const reasons = screen.getByRole("list", { name: "Why Florence art is recommended" });
  expect(within(reasons).getByText("You enjoyed culture activities")).toBeInTheDocument();
  expect(within(reasons).getByText("You liked travelling by train")).toBeInTheDocument();
  expect(screen.getByText(/650/)).toBeInTheDocument();
});

it("offers popular travels to newcomers without reasons", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ strategy: "POPULAR", items: [{ travelId: "t2", title: "Rhodes sun",
    startDate: "2026-11-27", endDate: "2026-12-01", price: null, score: 0, reasons: [] }] })));
  render(<Recommendations context={context} />);
  expect(await screen.findByRole("heading", { name: "Popular upcoming travels" })).toBeInTheDocument();
  expect(screen.getByText("Rhodes sun")).toBeInTheDocument();
  expect(screen.queryByRole("list", { name: /Why/ })).not.toBeInTheDocument();
});

it("keeps the page usable when recommendations are unavailable", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ code: "DEPENDENCY_UNAVAILABLE", message: "down", fieldErrors: [], traceId: "" }, 503)));
  render(<Recommendations context={context} />);
  expect(await screen.findByText(/Recommendations are not available right now/)).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("words every reason field", () => {
  expect(reasonText({ field: "DESTINATION", value: "ROME" })).toBe("You travelled to Rome");
  expect(reasonText({ field: "ACCOMMODATION_CATEGORY", value: "HOTEL" })).toBe("You stayed in a hotel");
});
