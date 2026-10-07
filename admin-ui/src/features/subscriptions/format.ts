import type { SubscriptionStatus } from "../../lib/types";

export const statusLabels: Record<SubscriptionStatus, string> = {
  PENDING_PAYMENT: "Awaiting payment",
  CONFIRMED: "Confirmed",
  PAYMENT_FAILED: "Payment failed",
  CANCELLED_BY_TRAVELER: "Cancelled by you",
  CANCELLED_BY_MANAGER: "Removed by the organiser",
  COMPLETED: "Completed",
};

export function isActive(status: SubscriptionStatus): boolean {
  return status === "PENDING_PAYMENT" || status === "CONFIRMED";
}

export function statusClass(status: SubscriptionStatus): string {
  if (status === "CONFIRMED" || status === "COMPLETED") return "status active";
  if (status === "PENDING_PAYMENT") return "status pending";
  return "status failed";
}

/** Formats integer minor units (e.g. cents) in the currency's own decimals. */
export function formatMoney(amountMinor: number, currency: string): string {
  try {
    const format = new Intl.NumberFormat(undefined, { style: "currency", currency });
    const digits = format.resolvedOptions().maximumFractionDigits ?? 2;
    return format.format(amountMinor / 10 ** digits);
  } catch {
    return `${(amountMinor / 100).toFixed(2)} ${currency}`;
  }
}

export function formatPrice(price: number, currency: string): string {
  try { return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(price); }
  catch { return `${price} ${currency}`; }
}

/** The last travel-local date a traveler may cancel: three calendar days before the start. */
export function cutoffDate(startDate: string): string {
  const date = new Date(`${startDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - 3);
  return date.toISOString().slice(0, 10);
}
