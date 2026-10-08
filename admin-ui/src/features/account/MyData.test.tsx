import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MyData, collectMyData } from "./MyData";

const context = { token: "t", onUnauthorized: () => {} };
const json = (data: unknown) => new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });

function stubApi() {
  const asked: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    asked.push(path);
    if (path === "/api/me") return json({ userId: "u1", email: "ada@example.com" });
    if (path.startsWith("/api/travelers?")) return json({ items: [{ id: "p1" }], page: 0, size: 100, total: 1 });
    if (path.startsWith("/api/subscriptions?")) return json({ items: [{ subscriptionId: "s1" }], totalPages: 1 });
    if (path.startsWith("/api/feedback/mine?") || path.startsWith("/api/reports?")) return json({ items: [], totalPages: 0 });
    if (path === "/api/subscription-payments") return json([{ paymentId: "pay1" }]);
    if (path === "/api/dashboards/me") return json({ completedTrips: 1 });
    throw new Error(`Unexpected request: ${path}`);
  }));
  return asked;
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("collects the person's own records from every service", async () => {
  stubApi();
  const data = await collectMyData(context);
  expect(data.account).toEqual({ userId: "u1", email: "ada@example.com" });
  expect(data.travelerProfiles).toEqual([{ id: "p1" }]);
  expect(data.subscriptions).toEqual([{ subscriptionId: "s1" }]);
  expect(data.payments).toEqual([{ paymentId: "pay1" }]);
  expect(data.statistics).toEqual({ completedTrips: 1 });
});

it("downloads the export as a JSON file", async () => {
  stubApi();
  const created = vi.fn((blob: Blob) => { void blob; return "blob:export"; });
  vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: created, revokeObjectURL: vi.fn() }));
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  render(<MyData context={context} />);
  await userEvent.setup().click(screen.getByRole("button", { name: "Download my data" }));
  expect(await screen.findByText("Your data was downloaded.")).toBeInTheDocument();
  expect(click).toHaveBeenCalledOnce();
  expect(JSON.parse(await (created.mock.calls[0][0]).text()).account.email).toBe("ada@example.com");
});
