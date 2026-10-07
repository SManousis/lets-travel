import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TravelerTrips } from "./TravelerTrips";
import type { PaymentOptions, Subscription, SubscriptionPayment } from "../../lib/types";

const context = { token: "test-token", onUnauthorized: () => {} };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
}

function subscription(overrides: Partial<Subscription> = {}): Subscription {
  return {
    subscriptionId: "sub-1", travelId: "travel-1", travelTitle: "Crete in spring", travelerId: "me", managerId: "manager-1",
    status: "PENDING_PAYMENT", price: { amountMinor: 45050, currency: "EUR" }, startDate: "2026-11-20",
    endDate: "2026-11-26", timeZone: "Europe/Athens", cancellationCutoff: "2026-11-17", travelerMayCancel: true,
    paymentId: null, reservationExpiresAt: "2026-10-07T09:30:00Z", cancellationReason: null, cancelledAt: null,
    createdAt: "2026-10-07T09:00:00Z", updatedAt: "2026-10-07T09:00:00Z", ...overrides,
  };
}

function payment(overrides: Partial<SubscriptionPayment> = {}): SubscriptionPayment {
  return {
    paymentId: "pay-1", subscriptionId: "sub-1", amountMinor: 45050, currency: "EUR", provider: "STRIPE",
    methodDisplay: "visa •••• 4242", state: "SUCCEEDED", failureCode: null,
    message: "Payment received. Your place is confirmed.", refundReason: null,
    createdAt: "2026-10-07T09:05:00Z", updatedAt: "2026-10-07T09:05:00Z", ...overrides,
  };
}

const options: PaymentOptions = {
  methods: [{ id: "method-1", provider: "STRIPE", displayLabel: "Travel card", displayMetadata: "visa •••• 4242", isDefault: true, available: true }],
  testProviderEnabled: true,
};

type Handler = (path: string, init?: RequestInit) => Response | Promise<Response>;

/** A fake backend: subscriptions and payments change as the traveler pays. */
function backend(state: { mine: Subscription[]; payments: SubscriptionPayment[] }, handle: Handler) {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith("/api/travels?")) return json({ items: [], page: 0, size: 50, total: 0 });
    if (path.startsWith("/api/subscriptions?")) return json({ items: state.mine, page: 0, size: 100, totalElements: state.mine.length, totalPages: 1 });
    if (path === "/api/subscription-payments" && init?.method !== "POST") return json(state.payments);
    if (path === "/api/payment-options") return json(options);
    return handle(path, init);
  }));
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("pays with the default saved method, sending only IDs and an idempotency key", async () => {
  const user = userEvent.setup();
  const state = { mine: [subscription()], payments: [] as SubscriptionPayment[] };
  const posts: { key: string | null; body: unknown }[] = [];
  backend(state, (path, init) => {
    if (path === "/api/subscription-payments" && init?.method === "POST") {
      posts.push({ key: new Headers(init.headers).get("Idempotency-Key"), body: JSON.parse(String(init.body)) });
      state.mine = [subscription({ status: "CONFIRMED", paymentId: "pay-1", reservationExpiresAt: null })];
      state.payments = [payment()];
      return json(payment(), 201);
    }
    throw new Error(`Unexpected request: ${path}`);
  });

  render(<TravelerTrips context={context} />);
  await user.click(await screen.findByRole("button", { name: "Pay now" }));
  const form = within(await screen.findByRole("form", { name: "Pay for Crete in spring" }));
  expect(form.getByRole("radio", { name: /Travel card \(visa •••• 4242\)/ })).toBeChecked();
  await user.click(form.getByRole("button", { name: /Pay €450\.50|Pay 450[.,]50/ }));

  expect(await screen.findByText("Payment received. Your place is confirmed.")).toBeInTheDocument();
  expect(posts).toEqual([{ key: expect.stringMatching(/^[0-9a-f-]{36}$/), body: { subscriptionId: "sub-1", paymentMethodId: "method-1" } }]);
  expect(await screen.findByText("visa •••• 4242: Paid")).toBeInTheDocument();
  expect(screen.getByText("Confirmed")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Pay now" })).not.toBeInTheDocument();
});

it("shows a declined test payment as a safe failure and the released place", async () => {
  const user = userEvent.setup();
  const declined = payment({ provider: "TEST", methodDisplay: "Test card (simulated decline)", state: "FAILED",
    failureCode: "PAYMENT_DECLINED", message: "The payment was declined, so your reserved place was released. "
      + "You can subscribe again and use another payment method." });
  const state = { mine: [subscription()], payments: [] as SubscriptionPayment[] };
  const bodies: unknown[] = [];
  backend(state, (path, init) => {
    if (path === "/api/subscription-payments" && init?.method === "POST") {
      bodies.push(JSON.parse(String(init.body)));
      state.mine = [subscription({ status: "PAYMENT_FAILED", travelerMayCancel: false, reservationExpiresAt: null })];
      state.payments = [declined];
      return json(declined, 201);
    }
    throw new Error(`Unexpected request: ${path}`);
  });

  render(<TravelerTrips context={context} />);
  await user.click(await screen.findByRole("button", { name: "Pay now" }));
  await user.click(await screen.findByRole("radio", { name: "Test card (simulation, no real money)" }));
  await user.selectOptions(screen.getByLabelText("Simulated result"), "DECLINE");
  await user.click(screen.getByRole("button", { name: /^Pay / }));

  expect(await screen.findByRole("alert")).toHaveTextContent("The payment was declined");
  expect(bodies).toEqual([{ subscriptionId: "sub-1", testOutcome: "DECLINE" }]);
  expect(await screen.findByText("Test card (simulated decline): Payment failed")).toBeInTheDocument();
  expect(screen.getByText("Payment failed")).toBeInTheDocument();
});

it("keeps one key across a network retry and checks a payment that is still being confirmed", async () => {
  const user = userEvent.setup();
  const pending = payment({ provider: "TEST", methodDisplay: "Test card (simulated timeout)", state: "REQUIRES_RECONCILIATION",
    message: "We are confirming this payment with the provider. You will not be charged twice; check again in a few minutes." });
  const state = { mine: [subscription()], payments: [] as SubscriptionPayment[] };
  const keys: (string | null)[] = [];
  backend(state, (path, init) => {
    if (path === "/api/subscription-payments" && init?.method === "POST") {
      keys.push(new Headers(init.headers).get("Idempotency-Key"));
      if (keys.length === 1) throw new Error("Network timeout");
      state.payments = [pending];
      return json(pending, 201);
    }
    if (path === "/api/subscription-payments/pay-1") {
      state.mine = [subscription({ status: "CONFIRMED", paymentId: "pay-1" })];
      state.payments = [payment({ provider: "TEST", methodDisplay: "Test card (simulated timeout)" })];
      return json(state.payments[0]);
    }
    throw new Error(`Unexpected request: ${path}`);
  });

  render(<TravelerTrips context={context} />);
  await user.click(await screen.findByRole("button", { name: "Pay now" }));
  await user.click(await screen.findByRole("radio", { name: "Test card (simulation, no real money)" }));
  await user.selectOptions(screen.getByLabelText("Simulated result"), "TIMEOUT");
  await user.click(screen.getByRole("button", { name: /^Pay / }));
  expect(await screen.findByRole("alert")).toHaveTextContent("could not be reached");
  await user.click(screen.getByRole("button", { name: /^Pay / }));

  expect(await screen.findByText(/You will not be charged twice/)).toBeInTheDocument();
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);

  await user.click(screen.getByRole("button", { name: "Check payment status" }));
  expect(await screen.findByText("Payment received. Your place is confirmed.")).toBeInTheDocument();
  expect(await screen.findByText("Test card (simulated timeout): Paid")).toBeInTheDocument();
});

it("shows payment and refund state on my subscriptions", async () => {
  const state = {
    mine: [
      subscription({ subscriptionId: "sub-1", travelTitle: "Crete", status: "CANCELLED_BY_TRAVELER", travelerMayCancel: false }),
      subscription({ subscriptionId: "sub-2", travelTitle: "Lisbon", status: "CANCELLED_BY_MANAGER", travelerMayCancel: false,
        cancellationReason: "Trip rescheduled" }),
      subscription({ subscriptionId: "sub-3", travelTitle: "Rome", status: "PAYMENT_FAILED", travelerMayCancel: false }),
    ],
    payments: [
      payment({ subscriptionId: "sub-1", state: "REFUNDED", refundReason: "SUBSCRIPTION_CANCELLED" }),
      payment({ paymentId: "pay-2", subscriptionId: "sub-2", state: "SUCCEEDED" }),
    ],
  };
  backend(state, path => { throw new Error(`Unexpected request: ${path}`); });

  render(<TravelerTrips context={context} />);

  expect(await screen.findByText("visa •••• 4242: Refunded in full")).toBeInTheDocument();
  expect(screen.getByText("visa •••• 4242: a full refund is being arranged.")).toBeInTheDocument();
  expect(screen.getByText("No payment was taken. The reservation expired or the payment failed.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Pay now" })).not.toBeInTheDocument();
});
