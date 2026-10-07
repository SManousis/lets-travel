import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TravelSearch } from "./TravelSearch";
import { TravelerTrips } from "../subscriptions/TravelerTrips";
import { autocompletePath, searchPath } from "../../lib/api";
import type { SearchPage, TravelHit, TravelOffer } from "../../lib/types";

const context = { token: "test-token", onUnauthorized: () => {} };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

const hit: TravelHit = {
  travelId: "travel-1", title: "Crete Island Escape", description: "A week on the south coast.",
  destinations: ["Chaniá", "Heraklion"], countries: ["Greece"], startDate: "2026-11-20", endDate: "2026-11-26",
  durationDays: 7, price: 450, currency: "EUR", remainingPlaces: 8, managerId: "manager-1", managerName: "Eleni Markou",
  activities: ["Sea kayaking"], transportModes: ["FERRY"], highlights: ["Sea kayaking"],
};

function results(items: TravelHit[], page = 0, totalPages = 1): SearchPage {
  return { items, page, size: 10, totalElements: items.length, totalPages, indexedAt: "2026-10-07T10:00:00Z" };
}

const offer: TravelOffer = {
  id: "travel-1", managerId: "manager-1", name: "Crete Island Escape", description: "A week on the south coast.",
  startDate: "2026-11-20", endDate: "2026-11-26", durationDays: 7, capacity: 10, price: 450, currency: "EUR",
  status: "PUBLISHED", participantCount: 2, remainingCapacity: 8, bookable: true, timeZone: "Europe/Athens",
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("builds search and autocomplete URLs without empty filters", () => {
  expect(searchPath(" crete ", { country: "Greece", transportMode: "", currency: "" }, 1, 10))
    .toBe("/api/search/travels?q=crete&country=Greece&page=1&size=10");
  expect(autocompletePath("kay")).toBe("/api/search/autocomplete?q=kay&limit=8");
});

it("debounces autocomplete and asks only for the last text typed", async () => {
  const user = userEvent.setup();
  const asked: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/search/autocomplete") {
      asked.push(url.searchParams.get("q") ?? "");
      return json({ items: [
        { text: "Crete Island Escape", type: "TRAVEL", travelId: "travel-1", context: "2026-11-20 to 2026-11-26 · Chaniá" },
        { text: "Sea kayaking", type: "ACTIVITY", travelId: null, context: "Activity on Crete Island Escape" },
      ] });
    }
    throw new Error(`Unexpected request: ${url}`);
  }));

  render(<TravelSearch context={context} onOpen={() => {}} />);
  await user.type(screen.getByRole("searchbox"), "cre");
  const list = await screen.findByRole("listbox", { name: "Suggestions" });
  expect(asked).toEqual(["cre"]);
  expect(within(list).getByText("Crete Island Escape")).toBeInTheDocument();
  expect(within(list).getByText("Travel · 2026-11-20 to 2026-11-26 · Chaniá")).toBeInTheDocument();
  expect(within(list).getByText("Activity · Activity on Crete Island Escape")).toBeInTheDocument();
});

it("does not ask for suggestions before two characters", async () => {
  const user = userEvent.setup();
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  render(<TravelSearch context={context} onOpen={() => {}} />);
  await user.type(screen.getByRole("searchbox"), "c");
  await new Promise(resolve => setTimeout(resolve, 400));
  expect(fetchMock).not.toHaveBeenCalled();
});

it("searches with the chosen filters and opens a result", async () => {
  const user = userEvent.setup();
  const searched: URL[] = [];
  const onOpen = vi.fn();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/search/autocomplete") return json({ items: [] });
    if (url.pathname === "/api/search/travels") { searched.push(url); return json(results([hit])); }
    throw new Error(`Unexpected request: ${url}`);
  }));

  render(<TravelSearch context={context} onOpen={onOpen} />);
  await user.type(screen.getByRole("searchbox"), "kayaking");
  await user.type(screen.getByLabelText("Country"), "Greece");
  await user.selectOptions(screen.getByLabelText("Transport"), "FERRY");
  await user.type(screen.getByLabelText("Max price"), "500");
  await user.click(screen.getByRole("button", { name: "Search" }));

  expect(await screen.findByText("1 travel found")).toBeInTheDocument();
  const params = searched[0].searchParams;
  expect(params.get("q")).toBe("kayaking");
  expect(params.get("country")).toBe("Greece");
  expect(params.get("transportMode")).toBe("FERRY");
  expect(params.get("maxPrice")).toBe("500");
  expect(params.get("currency")).toBe("EUR");
  expect(params.has("minPrice")).toBe(false);
  expect(screen.getByText("2026-11-20 – 2026-11-26 · Chaniá, Heraklion")).toBeInTheDocument();
  expect(screen.getByText("“Sea kayaking”")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "View and subscribe: Crete Island Escape" }));
  expect(onOpen).toHaveBeenCalledWith("travel-1");
});

it("picking a place suggestion runs a search for it; picking a travel opens it", async () => {
  const user = userEvent.setup();
  const onOpen = vi.fn();
  const searched: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/search/autocomplete") return json({ items: [
      { text: "Crete Island Escape", type: "TRAVEL", travelId: "travel-1", context: "dates" },
      { text: "Greece", type: "COUNTRY", travelId: null, context: "Country" },
    ] });
    if (url.pathname === "/api/search/travels") { searched.push(url.searchParams.get("q") ?? ""); return json(results([hit])); }
    throw new Error(`Unexpected request: ${url}`);
  }));

  render(<TravelSearch context={context} onOpen={onOpen} />);
  await user.type(screen.getByRole("searchbox"), "gre");
  await user.click(await screen.findByRole("button", { name: /Greece/ }));
  await screen.findByText("1 travel found");
  expect(searched).toEqual(["Greece"]);
  expect(screen.getByRole("searchbox")).toHaveValue("Greece");

  await user.clear(screen.getByRole("searchbox"));
  await user.type(screen.getByRole("searchbox"), "cre");
  await user.click(await screen.findByRole("button", { name: /Crete Island Escape/ }));
  expect(onOpen).toHaveBeenCalledWith("travel-1");
});

it("falls back to the travel list when search is unavailable", async () => {
  const user = userEvent.setup();
  const browsed: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/search/autocomplete") return json({ code: "SEARCH_UNAVAILABLE", message: "down" }, 503);
    if (url.pathname === "/api/search/travels") return json({ code: "SEARCH_UNAVAILABLE", message: "down", fieldErrors: [], traceId: "" }, 503);
    if (url.pathname === "/api/travels") {
      browsed.push(url.search);
      return json({ items: [offer, { ...offer, id: "travel-2", name: "Lisbon City Break", description: "Trams" }], page: 0, size: 100, total: 2 });
    }
    throw new Error(`Unexpected request: ${url}`);
  }));

  render(<TravelSearch context={context} onOpen={() => {}} />);
  await user.type(screen.getByRole("searchbox"), "crete");
  await user.click(screen.getByRole("button", { name: "Search" }));

  expect(await screen.findByText(/Search is temporarily limited/)).toBeInTheDocument();
  expect(browsed).toEqual(["?page=0&size=100"]);
  expect(screen.getByText("Crete Island Escape")).toBeInTheDocument();
  expect(screen.queryByText("Lisbon City Break")).not.toBeInTheDocument();
  expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
});

it("shows validation problems from the search service", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/search/autocomplete") return json({ items: [] });
    return json({ code: "INVALID_QUERY", message: "Search parameters are not valid", fieldErrors: [], traceId: "t" }, 400);
  }));
  render(<TravelSearch context={context} onOpen={() => {}} />);
  await user.click(screen.getByRole("button", { name: "Search" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("INVALID_QUERY");
});

it("pages through results", async () => {
  const user = userEvent.setup();
  const pages: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    const page = Number(url.searchParams.get("page"));
    pages.push(String(page));
    return json(results([{ ...hit, travelId: `travel-${page}`, title: `Trip ${page}` }], page, 2));
  }));
  render(<TravelSearch context={context} onOpen={() => {}} />);
  await user.click(screen.getByRole("button", { name: "Search" }));
  await screen.findByText("Trip 0");
  await user.click(screen.getByRole("button", { name: "Next" }));
  await screen.findByText("Trip 1");
  expect(screen.getByText("Page 2 of 2")).toBeInTheDocument();
  expect(pages).toEqual(["0", "1"]);
});

it("opens a search result as the travel detail with Subscribe in the traveler view", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/travels") return json({ items: [], page: 0, size: 50, total: 0 });
    if (url.pathname === "/api/subscriptions") return json({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });
    if (url.pathname === "/api/subscription-payments") return json([]);
    if (url.pathname === "/api/search/autocomplete") return json({ items: [] });
    if (url.pathname === "/api/search/travels") return json(results([hit]));
    if (url.pathname === "/api/travels/travel-1") return json(offer);
    throw new Error(`Unexpected request: ${url}`);
  }));

  render(<TravelerTrips context={context} />);
  await user.click(screen.getByRole("button", { name: "Search" }));
  await user.click(await screen.findByRole("button", { name: "View and subscribe: Crete Island Escape" }));
  const detail = (await screen.findByRole("heading", { name: "Crete Island Escape" })).closest("section") as HTMLElement;
  await waitFor(() => expect(within(detail).getByRole("button", { name: "Subscribe" })).toBeEnabled());
  expect(within(detail).getByText("8 of 10")).toBeInTheDocument();
});
