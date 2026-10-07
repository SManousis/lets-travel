import type { SubscriptionPayment, SubscriptionPaymentState, SubscriptionStatus } from "../../lib/types";

export const paymentLabels: Record<SubscriptionPaymentState, string> = {
  PROCESSING: "Payment processing",
  REQUIRES_RECONCILIATION: "Payment being confirmed with the provider",
  SUCCEEDED: "Paid",
  FAILED: "Payment failed",
  REFUND_PENDING: "Refund in progress",
  REFUNDED: "Refunded in full",
};

export function paymentClass(state: SubscriptionPaymentState): string {
  if (state === "SUCCEEDED" || state === "REFUNDED") return "status active";
  if (state === "FAILED") return "status failed";
  return "status pending";
}

/** Payment still unresolved: the traveler should check again rather than pay again. */
export function isUnresolved(payment: SubscriptionPayment): boolean {
  return payment.state === "PROCESSING" || payment.state === "REQUIRES_RECONCILIATION";
}

/** Text for the payment line on "My subscriptions", including the refund rule after a cancellation. */
export function paymentSummary(payment: SubscriptionPayment | undefined, status: SubscriptionStatus): string | null {
  if (!payment) return status === "PAYMENT_FAILED" ? "No payment was taken. The reservation expired or the payment failed." : null;
  const cancelled = status === "CANCELLED_BY_TRAVELER" || status === "CANCELLED_BY_MANAGER";
  if (cancelled && payment.state === "SUCCEEDED") return `${payment.methodDisplay}: a full refund is being arranged.`;
  return `${payment.methodDisplay}: ${paymentLabels[payment.state]}`;
}
