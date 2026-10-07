export type User = {
  id: string; email: string; firstName: string; lastName: string;
  role: "ADMIN" | "TRAVEL_MANAGER" | "TRAVELER"; status: "ACTIVE" | "DISABLED" | "PENDING" | "FAILED";
  createdAt: string;
};

export type Traveler = {
  id: string; userId: string; passportNumber: string; nationality: string; createdAt: string;
};

export type Destination = { id: string; name: string; country: string; createdAt: string };
export type Stop = { id: string; destinationId: string; position: number; arrivalDate: string | null; departureDate: string | null };
export type Activity = { id: string; name: string; description: string; date: string };
export type Accommodation = { id: string; name: string; address: string; checkIn: string; checkOut: string };
export type Transport = { id: string; mode: string; departure: string; arrival: string; date: string };
export type Travel = {
  id: string; name: string; startDate: string; endDate: string; durationDays: number;
  stops: Stop[]; activities: Activity[]; accommodations: Accommodation[]; transports: Transport[];
  travelerIds: string[]; createdAt: string;
};

export type Gateway = { id: string; provider: "STRIPE" | "PAYPAL"; displayName: string; enabled: boolean; createdAt: string };
export type PaymentMethod = {
  id: string; userId: string; gatewayId: string; provider: "STRIPE" | "PAYPAL";
  displayLabel: string; isDefault: boolean; status: "PENDING" | "ACTIVE" | "FAILED" | "DELETION_PENDING";
  displayMetadata: string | null; failureCode: string | null; createdAt: string;
};

/** A travel as travel-service returns it since Phase 2 (travelers only see bookable ones). */
export type TravelOffer = {
  id: string; managerId: string; name: string; description: string; startDate: string; endDate: string;
  durationDays: number; capacity: number; price: number; currency: string;
  status: "DRAFT" | "PUBLISHED" | "UNPUBLISHED" | "CANCELLED";
  participantCount: number; remainingCapacity: number; bookable: boolean; timeZone: string;
};

export type SubscriptionStatus = "PENDING_PAYMENT" | "CONFIRMED" | "PAYMENT_FAILED"
  | "CANCELLED_BY_TRAVELER" | "CANCELLED_BY_MANAGER" | "COMPLETED";

export type Subscription = {
  subscriptionId: string; travelId: string; travelTitle: string; travelerId: string; managerId: string;
  status: SubscriptionStatus; price: { amountMinor: number; currency: string };
  startDate: string; endDate: string; timeZone: string; cancellationCutoff: string; travelerMayCancel: boolean;
  paymentId: string | null; reservationExpiresAt: string | null; cancellationReason: string | null;
  cancelledAt: string | null; createdAt: string; updatedAt: string;
};

/** Safe profile summary a travel's manager sees; no contact details. */
export type Subscriber = {
  subscriptionId: string; travelerId: string; displayName: string; memberSince: string | null;
  completedTrips: number; reportCount: number; status: SubscriptionStatus; subscribedAt: string;
  cancellationReason: string | null; cancelledAt: string | null;
};

/** Pagination used by the engagement API. */
export type ContractPage<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };

export type SubscriptionPaymentState = "PROCESSING" | "REQUIRES_RECONCILIATION" | "SUCCEEDED" | "FAILED"
  | "REFUND_PENDING" | "REFUNDED";

/** A subscription payment as payment-service returns it: safe display data only, never card details. */
export type SubscriptionPayment = {
  paymentId: string; subscriptionId: string; amountMinor: number; currency: string;
  provider: "STRIPE" | "PAYPAL" | "TEST"; methodDisplay: string; state: SubscriptionPaymentState;
  failureCode: string | null; message: string; refundReason: string | null; createdAt: string; updatedAt: string;
};

export type TestOutcome = "APPROVE" | "DECLINE" | "TIMEOUT";

export type PaymentOptions = {
  methods: { id: string; provider: "STRIPE" | "PAYPAL"; displayLabel: string; displayMetadata: string | null;
    isDefault: boolean; available: boolean }[];
  testProviderEnabled: boolean;
};
