import type { Me } from "./api";

export type Role = Me["role"];

export type RouteId =
  | "home" | "travels" | "feedback" | "reports" | "statistics"
  | "listings" | "subscribers" | "travel-feedback" | "analytics"
  | "overview" | "rankings" | "history" | "users" | "moderation" | "payments";

type RouteInfo = { label: string; roles: Role[] };

/**
 * Every screen and the roles that may open it, in navigation order. The server checks every request
 * again; hiding a screen only keeps people from reaching actions their role cannot perform.
 */
export const routes: Record<RouteId, RouteInfo> = {
  overview: { label: "Overview", roles: ["ADMIN"] },
  rankings: { label: "Manager rankings", roles: ["ADMIN"] },
  history: { label: "Travel history", roles: ["ADMIN"] },
  users: { label: "Users", roles: ["ADMIN"] },
  home: { label: "Home", roles: ["TRAVELER", "TRAVEL_MANAGER"] },
  travels: { label: "Find and book", roles: ["TRAVELER", "TRAVEL_MANAGER"] },
  listings: { label: "Travels", roles: ["TRAVEL_MANAGER", "ADMIN"] },
  subscribers: { label: "Subscribers", roles: ["TRAVEL_MANAGER", "ADMIN"] },
  "travel-feedback": { label: "Travel feedback", roles: ["TRAVEL_MANAGER", "ADMIN"] },
  analytics: { label: "Analytics", roles: ["TRAVEL_MANAGER"] },
  moderation: { label: "Reports", roles: ["ADMIN"] },
  payments: { label: "Payments", roles: ["ADMIN"] },
  feedback: { label: "My feedback", roles: ["TRAVELER", "TRAVEL_MANAGER"] },
  reports: { label: "My reports", roles: ["TRAVELER", "TRAVEL_MANAGER"] },
  statistics: { label: "My statistics", roles: ["TRAVELER", "TRAVEL_MANAGER", "ADMIN"] },
};

export function routesFor(role: Role): RouteId[] {
  return (Object.keys(routes) as RouteId[]).filter(id => routes[id].roles.includes(role));
}

export function homeFor(role: Role): RouteId {
  return role === "ADMIN" ? "overview" : "home";
}

export type Resolved = { kind: "page"; route: RouteId } | { kind: "forbidden"; label: string } | { kind: "missing" };

/** Turns the address bar's `#/name` into the screen to show for this role. */
export function resolveRoute(hash: string, role: Role): Resolved {
  const name = hash.replace(/^#\/?/, "").split("?")[0];
  if (!name) return { kind: "page", route: homeFor(role) };
  if (!Object.hasOwn(routes, name)) return { kind: "missing" };
  const route = name as RouteId;
  return routes[route].roles.includes(role) ? { kind: "page", route } : { kind: "forbidden", label: routes[route].label };
}

export function href(route: RouteId): string {
  return `#/${route}`;
}
