import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { ApiContext } from "../../lib/api";
import type { ReportDetail } from "../../lib/types";
import { MyReports } from "./MyReports";
import { ReportForm } from "./ReportForm";
import { ReportQueue } from "./ReportQueue";

const context: ApiContext = { token: "token", onUnauthorized: vi.fn() };
const managerId = "11111111-1111-4111-8111-111111111111";

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

function page<T>(items: T[]) {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: 1 };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("files a report with one idempotency key that survives a retry", async () => {
  const user = userEvent.setup();
  const posts: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    expect(String(input)).toBe("/api/reports");
    posts.push({ key: new Headers(options?.headers).get("Idempotency-Key"), body: JSON.parse(String(options?.body)) });
    if (posts.length === 1) throw new Error("network down");
    return json({ reportId: "r1" }, 201);
  }));
  const onFiled = vi.fn();
  render(<ReportForm context={context} target={{ type: "TRAVEL_MANAGER", id: managerId, name: "Grace Hopper" }} onFiled={onFiled} />);

  await user.selectOptions(screen.getByLabelText("Reason"), "FRAUD");
  await user.type(screen.getByLabelText("What happened"), "Took payment and cancelled the trip.");
  await user.click(screen.getByRole("button", { name: "Send report" }));
  expect(await screen.findByRole("alert")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Send report" }));

  expect(await screen.findByText(/Your report was sent/)).toBeInTheDocument();
  expect(posts).toHaveLength(2);
  expect(posts[1].key).toBe(posts[0].key);
  expect(posts[1].body).toEqual({ targetType: "TRAVEL_MANAGER", targetId: managerId, category: "FRAUD",
    description: "Took payment and cancelled the trip." });
  expect(onFiled).toHaveBeenCalledOnce();
});

it("explains the report rules in plain language", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async () => json({ code: "DUPLICATE_REPORT", message: "Open report exists",
    fieldErrors: [], traceId: "t" }, 409)));
  render(<ReportForm context={context} target={{ type: "TRAVEL", id: managerId, name: "Island hopping" }} />);

  await user.type(screen.getByLabelText("What happened"), "The itinerary was not as described.");
  await user.click(screen.getByRole("button", { name: "Send report" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("You already have an open report about this.");
});

it("does not send a description shorter than 10 characters", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn());
  render(<ReportForm context={context} target={{ type: "TRAVELER", id: managerId, name: "Ada" }} onClose={vi.fn()} />);
  await user.type(screen.getByLabelText("What happened"), "short");
  expect(screen.getByRole("button", { name: "Send report" })).toBeDisabled();
  expect(fetch).not.toHaveBeenCalled();
});

it("lists the traveler's own reports and reports by ID", async () => {
  const user = userEvent.setup();
  const posted: unknown[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    if (String(input).startsWith("/api/reports?")) {
      return json(page([{ reportId: "r1", targetType: "TRAVEL", targetId: "t1", targetDisplayName: "Island hopping",
        category: "MISLEADING_LISTING", description: "Hotel was not the one listed.", status: "IN_REVIEW",
        createdAt: "2026-10-07T09:00:00Z" }]));
    }
    if (String(input) === "/api/reports" && options?.method === "POST") {
      posted.push(JSON.parse(String(options.body)));
      return json({ reportId: "r2" }, 201);
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  }));
  render(<MyReports context={context} />);

  expect(await screen.findByText("Island hopping")).toBeInTheDocument();
  expect(screen.getByText(/Misleading listing · In review/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
  await user.type(screen.getByLabelText("ID"), managerId);
  await user.click(screen.getByRole("button", { name: "Continue" }));
  await user.type(screen.getByLabelText("What happened"), "Rude and threatening messages.");
  await user.click(screen.getByRole("button", { name: "Send report" }));

  await screen.findByText(/Your report was sent/);
  expect(posted).toEqual([{ targetType: "TRAVEL_MANAGER", targetId: managerId, category: "OTHER",
    description: "Rude and threatening messages." }]);
});

it("lets an admin moderate an open report with a note and shows the history", async () => {
  const user = userEvent.setup();
  const open: ReportDetail = { reportId: "r1", reporterId: "22222222-2222-4222-8222-222222222222", targetType: "TRAVEL_MANAGER",
    targetId: managerId, targetDisplayName: "Grace Hopper", category: "FRAUD", description: "Took payment and vanished.",
    status: "OPEN", assigneeId: null, history: [], createdAt: "2026-10-07T09:00:00Z" };
  const patches: unknown[] = [];
  const queries: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/admin/reports?")) { queries.push(path); return json(page([open])); }
    if (path === "/api/admin/reports/r1" && options?.method === "PATCH") {
      patches.push(JSON.parse(String(options.body)));
      return json({ ...open, status: "RESOLVED", assigneeId: "a1",
        history: [{ adminId: "a1aaaaaa-0000-4000-8000-000000000000", status: "RESOLVED", note: "Confirmed with bank", at: "2026-10-08T10:00:00Z" }] });
    }
    throw new Error(`Unexpected request: ${path}`);
  }));
  render(<ReportQueue context={context} />);

  await user.click(await screen.findByRole("button", { name: "Open report about Grace Hopper" }));
  const detail = screen.getByRole("region", { name: "Report details" });
  expect(within(detail).getByText("Took payment and vanished.")).toBeInTheDocument();
  await user.selectOptions(within(detail).getByLabelText("Decision"), "RESOLVED");
  await user.type(within(detail).getByLabelText("Note"), "Confirmed with bank");
  await user.click(within(detail).getByRole("button", { name: "Save decision" }));

  expect(await screen.findByText("Report marked upheld.")).toBeInTheDocument();
  expect(patches).toEqual([{ status: "RESOLVED", note: "Confirmed with bank", assignToSelf: true }]);
  expect(within(detail).getByText("Confirmed with bank")).toBeInTheDocument();
  expect(within(detail).getByText("This report is closed. Its decision is final.")).toBeInTheDocument();
  expect(queries[0]).toBe("/api/admin/reports?status=OPEN&page=0&size=20");

  await user.selectOptions(screen.getByLabelText("About"), "TRAVEL");
  await waitFor(() => expect(queries.at(-1)).toBe("/api/admin/reports?status=OPEN&targetType=TRAVEL&page=0&size=20"));
});
