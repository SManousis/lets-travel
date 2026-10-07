import type { Subscription } from "../../lib/types";

/** "★★★★☆" for 4; screen readers get the aria-label set next to it instead. */
export function stars(rating: number): string {
  const whole = Math.max(0, Math.min(5, Math.round(rating)));
  return "★".repeat(whole) + "☆".repeat(5 - whole);
}

/** Today's date as YYYY-MM-DD in the browser's time zone. */
export function today(now = new Date()): string {
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
}

/**
 * Mirrors the server rule so the form is only offered when it can succeed: the trip is completed, or
 * confirmed and already over. The server re-checks in the travel's own time zone.
 */
export function mayLeaveFeedback(subscription: Subscription, on = today()): boolean {
  return subscription.status === "COMPLETED" || (subscription.status === "CONFIRMED" && subscription.endDate < on);
}
