import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminOverview, ManagerAnalytics, ManagerRankings, MyStatistics, money, rating } from "./Dashboards";

const context = { token: "token", onUnauthorized: () => {} };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("never adds different currencies together", () => {
  expect(money([{ amountMinor: 12345, currency: "EUR" }, { amountMinor: 500, currency: "USD" }]))
    .toBe(`${new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR" }).format(123.45)} + ${
      new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(5)}`);
  expect(rating({ average: null, count: 0 })).toBe("No ratings yet");
  expect(rating({ average: 4.25, count: 1 })).toBe("4.3 of 5 (1 rating)");
});

it("shows a traveler's own statistics and preferred payment methods", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ completedTrips: 3, upcomingTrips: 1, cancellations: 1, reportsFiled: 0,
    upheldReportsAgainstMe: 0, feedbackGiven: 2, preferredPaymentMethods: [{ provider: "STRIPE", payments: 4 }],
    asOf: "2026-10-08T10:00:00Z" })));
  render(<MyStatistics context={context} />);
  expect(await screen.findByText("Completed trips")).toBeInTheDocument();
  expect(screen.getByText("Completed trips").nextElementSibling).toHaveTextContent("3");
  expect(screen.getByText("Card (Stripe): 4 payments")).toBeInTheDocument();
});

it("asks for the chosen period and lists the manager's travels", async () => {
  const user = userEvent.setup();
  const asked: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    asked.push(String(input));
    return json({ managerId: "m1", incomeByMonth: [{ month: "2026-10", amounts: [{ amountMinor: 90000, currency: "EUR" }] }],
      totalTrips: 1, upcomingTrips: 0, totalTravelers: 2, cancellationRate: 0.25, rating: { average: 4.5, count: 2 },
      travels: [{ travelId: "t1", title: "Rome in spring", managerId: "m1", managerDisplayName: null, startDate: "2026-09-01",
        endDate: "2026-09-05", travelers: 2, cancellations: 1, income: [{ amountMinor: 90000, currency: "EUR" }],
        rating: { average: 4.5, count: 2 } }], asOf: "2026-10-08T10:00:00Z" });
  }));
  render(<ManagerAnalytics context={context} />);
  const table = await screen.findByRole("table", { name: /per travel/ });
  expect(within(table).getByRole("rowheader", { name: "Rome in spring" })).toBeInTheDocument();
  expect(screen.getByText("25%")).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("Period"), "12");
  await screen.findByRole("table", { name: /per travel/ });
  expect(asked).toEqual(["/api/dashboards/manager?months=6", "/api/dashboards/manager?months=12"]);
});

it("says when dashboards are temporarily unavailable and retries on request", async () => {
  const user = userEvent.setup();
  let calls = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/dashboards/admin/reconciliation") {
      return json([{ currency: "EUR", subscriptionNetMinor: 10000, paymentsSucceededMinor: 20000, refundPendingMinor: 0,
        difference: 10000, status: "DIFFERENT" }]);
    }
    calls++;
    if (calls === 1) return json({ code: "DEPENDENCY_UNAVAILABLE", message: "Down" }, 503);
    return json({ incomeByMonth: [], organizedTravels: 4, activeTravelers: 9, averageRating: { average: null, count: 0 },
      openReports: 0, topManagers: [], topTravels: [], asOf: "2026-10-08T10:00:00Z" });
  }));
  render(<AdminOverview context={context} />);
  const alert = await screen.findByText("Platform figures are temporarily unavailable. The rest of the app still works.");
  expect(await screen.findByText("EUR: differs")).toBeInTheDocument();
  await user.click(within(alert.closest("[role=alert]") as HTMLElement).getByRole("button", { name: "Try again" }));
  expect(await screen.findByText("Active travelers")).toBeInTheDocument();
});

it("shows each manager's score with its components", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ items: [{ rank: 1, managerId: "m1", displayName: "Ana", score: 0.975,
    components: { rating: 1, income: 1, completedTravels: 1, cancellationRate: 0.25, upheldReports: 0 }, organizedTravels: 2,
    income: [{ amountMinor: 130000, currency: "EUR" }], rating: { average: 4.5, count: 2 }, upheldReports: 0 }],
  page: 0, size: 20, totalElements: 1, totalPages: 1, asOf: "2026-10-08T10:00:00Z" })));
  render(<ManagerRankings context={context} />);
  const row = (await screen.findByRole("rowheader", { name: "Ana" })).closest("tr") as HTMLElement;
  expect(within(row).getByText("0.975")).toBeInTheDocument();
  expect(within(row).getByText("25%")).toBeInTheDocument();
});
