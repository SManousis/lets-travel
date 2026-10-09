import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ListingsPanel } from "./ListingsPanel";

const context = { token: "manager-token", onUnauthorized: () => {} };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

const draft = {
  id: "t1", managerId: "m1", name: "Rome in spring", description: "Walks and food", startDate: "2027-04-01", endDate: "2027-04-05",
  durationDays: 5, capacity: 10, price: 450, currency: "EUR", status: "DRAFT", participantCount: 0, remainingCapacity: 10,
  bookable: false, timeZone: "Europe/Rome", version: 1, stops: [], activities: [], accommodations: [], transports: [],
  createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
};

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("creates a draft listing with price, places and an itinerary, and cannot submit it twice", async () => {
  const user = userEvent.setup();
  const created: unknown[] = [];
  let finish: (response: Response) => void = () => {};
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/travels?page=0&size=10") return Promise.resolve(json({ items: [], page: 0, size: 10, total: 0 }));
    if (path === "/api/destinations?page=0&size=100") {
      return Promise.resolve(json({ items: [{ id: "d1", name: "Rome", country: "Italy", createdAt: "" }], page: 0, size: 100, total: 1 }));
    }
    if (path === "/api/travels" && options?.method === "POST") {
      created.push(JSON.parse(String(options.body)));
      return new Promise<Response>(resolve => { finish = resolve; });
    }
    throw new Error(`Unexpected request: ${options?.method ?? "GET"} ${path}`);
  }));

  render(<ListingsPanel context={context} />);
  expect(await screen.findByText("You have no travels yet. Create one with New travel.")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "New travel" }));
  await user.type(await screen.findByLabelText("Travel name"), "Rome in spring");
  fireEvent.change(screen.getByLabelText("Start date"), { target: { value: "2027-04-01" } });
  fireEvent.change(screen.getByLabelText("End date"), { target: { value: "2027-04-05" } });
  await user.clear(screen.getByLabelText("Places"));
  await user.type(screen.getByLabelText("Places"), "12");
  await user.type(screen.getByLabelText("Price per traveler"), "450");
  await user.type(screen.getByLabelText("Description"), "Walks and food");
  await user.selectOptions(screen.getByLabelText("Destination"), "d1");
  expect(screen.queryByLabelText("Organiser")).not.toBeInTheDocument();

  const save = screen.getByRole("button", { name: "Save travel" });
  await user.click(save);
  expect(screen.getByRole("button", { name: "Saving…" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Saving…" }));
  expect(created).toHaveLength(1);
  expect(created[0]).toMatchObject({ name: "Rome in spring", description: "Walks and food", startDate: "2027-04-01",
    endDate: "2027-04-05", capacity: 12, price: "450", currency: "EUR", timeZone: "UTC",
    stops: [{ destinationId: "d1", position: 0, arrivalDate: null, departureDate: null }] });
  expect(created[0]).not.toHaveProperty("managerId");

  finish(json(draft, 201));
  expect(await screen.findByRole("status")).toHaveTextContent("Rome in spring was created as a draft.");
});

it("explains why a listing cannot be published yet", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/travels?page=0&size=10") return json({ items: [draft], page: 0, size: 10, total: 1 });
    if (path === "/api/destinations?page=0&size=100") return json({ items: [], page: 0, size: 100, total: 0 });
    if (path === "/api/travels/t1/publication" && options?.method === "POST") {
      return json({ code: "PUBLICATION_NOT_READY", message: "Not ready" }, 409);
    }
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<ListingsPanel context={context} />);
  expect(await screen.findByText("Draft · 0 of 10 places taken")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Publish Rome in spring" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("This travel cannot be published yet");
});

it("lets admins choose the organiser of a new travel from active travel managers", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === "/api/travels?page=0&size=10") return json({ items: [], page: 0, size: 10, total: 0 });
    if (path === "/api/destinations?page=0&size=100") return json({ items: [], page: 0, size: 100, total: 0 });
    if (path === "/api/users?page=0&size=100") return json({ items: [
      { id: "m1", email: "mia@example.com", firstName: "Mia", lastName: "Manager", role: "TRAVEL_MANAGER", status: "ACTIVE", createdAt: "" },
      { id: "m2", email: "old@example.com", firstName: "Old", lastName: "Manager", role: "TRAVEL_MANAGER", status: "DISABLED", createdAt: "" },
      { id: "t1", email: "tom@example.com", firstName: "Tom", lastName: "Traveler", role: "TRAVELER", status: "ACTIVE", createdAt: "" },
    ], page: 0, size: 100, total: 3 });
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<ListingsPanel context={context} admin />);
  await screen.findByText("No travels yet.");
  await user.click(screen.getByRole("button", { name: "New travel" }));
  const organiser = await screen.findByLabelText("Organiser");
  expect([...organiser.querySelectorAll("option")].map(option => option.textContent))
    .toEqual(["Choose a travel manager", "Mia Manager (mia@example.com)"]);
  expect(screen.getByRole("heading", { name: "Destinations" })).toBeInTheDocument();
});

it("locks the booked terms of a travel that travelers have booked, and keeps the rest editable", async () => {
  const user = userEvent.setup();
  const booked = { ...draft, status: "PUBLISHED", participantCount: 2, remainingCapacity: 8,
    stops: [{ id: "s1", destinationId: "d1", position: 0, arrivalDate: null, departureDate: null }] };
  const saved: unknown[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === "/api/travels?page=0&size=10") return json({ items: [booked], page: 0, size: 10, total: 1 });
    if (path === "/api/destinations?page=0&size=100") {
      return json({ items: [{ id: "d1", name: "Rome", country: "Italy", createdAt: "" }], page: 0, size: 100, total: 1 });
    }
    if (path === "/api/travels/t1" && (options?.method ?? "GET") === "GET") return json(booked);
    if (path === "/api/travels/t1" && options?.method === "PUT") {
      saved.push(JSON.parse(String(options.body)));
      return json({ code: "TRAVEL_HAS_SUBSCRIBERS", message: "Booked" }, 409);
    }
    throw new Error(`Unexpected request: ${options?.method ?? "GET"} ${path}`);
  }));

  render(<ListingsPanel context={context} />);
  await user.click(await screen.findByRole("button", { name: "Edit Rome in spring" }));
  expect(await screen.findByText(/2 places are booked/)).toBeInTheDocument();
  for (const field of ["Start date", "End date", "Price per traveler", "Currency", "Time zone"]) {
    expect(screen.getByLabelText(field)).toBeDisabled();
  }
  expect(screen.getByLabelText("Places")).toBeEnabled();
  expect(screen.getByLabelText("Places")).toHaveAttribute("min", "2");
  expect(screen.getByLabelText("Description")).toBeEnabled();

  await user.type(screen.getByLabelText("Description"), " with a wine tasting");
  await user.click(screen.getByRole("button", { name: "Save travel" }));
  expect(saved[0]).toMatchObject({ startDate: "2027-04-01", endDate: "2027-04-05", price: "450", currency: "EUR",
    description: "Walks and food with a wine tasting" });
  expect(await screen.findByRole("alert")).toHaveTextContent("Travelers have booked this travel");
});
