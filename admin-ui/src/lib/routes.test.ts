import { expect, it } from "vitest";
import { resolveRoute, routesFor } from "./routes";

it("opens each role's home when no screen is named", () => {
  expect(resolveRoute("", "TRAVELER")).toEqual({ kind: "page", route: "home" });
  expect(resolveRoute("#/", "ADMIN")).toEqual({ kind: "page", route: "overview" });
});

it("refuses screens above the caller's role and unknown addresses", () => {
  expect(resolveRoute("#/listings", "TRAVELER")).toEqual({ kind: "forbidden", label: "Travels" });
  expect(resolveRoute("#/rankings", "TRAVEL_MANAGER")).toEqual({ kind: "forbidden", label: "Manager rankings" });
  expect(resolveRoute("#/analytics", "TRAVEL_MANAGER")).toEqual({ kind: "page", route: "analytics" });
  expect(resolveRoute("#/constructor", "ADMIN")).toEqual({ kind: "missing" });
});

it("gives every role its statistics and keeps admin-only screens to admins", () => {
  for (const role of ["TRAVELER", "TRAVEL_MANAGER", "ADMIN"] as const) expect(routesFor(role)).toContain("statistics");
  expect(routesFor("TRAVEL_MANAGER")).not.toContain("users");
  expect(routesFor("TRAVELER")).not.toContain("subscribers");
  expect(routesFor("ADMIN")).toEqual(["overview", "rankings", "history", "users", "listings", "subscribers",
    "travel-feedback", "moderation", "payments", "statistics"]);
});
