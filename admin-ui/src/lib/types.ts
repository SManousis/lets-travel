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
export type ActivityCategory = "CULTURE" | "NATURE" | "ADVENTURE" | "BEACH" | "FOOD" | "NIGHTLIFE" | "WELLNESS" | "SPORT";
export type AccommodationCategory = "HOTEL" | "HOSTEL" | "APARTMENT" | "RESORT" | "CAMPING";
export type Activity = { id: string; name: string; description: string; date: string; category: ActivityCategory | null };
export type Accommodation = { id: string; name: string; address: string; checkIn: string; checkOut: string; category: AccommodationCategory | null };
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

/** One search result from search-service (docs/api/search.yaml). */
export type TravelHit = {
  travelId: string; title: string; description: string; destinations: string[]; countries: string[];
  startDate: string; endDate: string; durationDays: number; price: number; currency: string;
  remainingPlaces: number; managerId: string; managerName: string | null; activities: string[];
  transportModes: string[]; highlights: string[];
};

export type SearchPage = {
  items: TravelHit[]; page: number; size: number; totalElements: number; totalPages: number; indexedAt: string | null;
};

export type Suggestion = {
  text: string; type: "TRAVEL" | "DESTINATION" | "COUNTRY" | "ACTIVITY"; travelId: string | null; context: string;
};

export type SearchSort = "RELEVANCE" | "START_DATE" | "PRICE_ASC" | "PRICE_DESC";

/** The filters the search API accepts; empty strings mean "not set". */
export type SearchFilters = {
  country: string; transportMode: string; startFrom: string; startTo: string;
  minPrice: string; maxPrice: string; currency: string; sort: SearchSort | "";
};

/** Feedback as engagement-service returns it. `comment` is plain text and is always rendered as text. */
export type Feedback = {
  feedbackId: string; travelId: string; travelTitle: string; managerId: string; authorDisplayName: string;
  rating: number; comment: string | null; createdAt: string;
};

/** A rating on the public manager page: the author is never shown. */
export type PublicRating = { travelTitle: string; rating: number; comment: string | null; createdAt: string };

export type ManagerPage = {
  managerId: string; displayName: string; memberSince: string; completedTravels: number; totalTravels: number;
  averageRating: number | null; ratingCount: number; recentRatings: PublicRating[]; reportCount: number;
};

export type ReportTargetType = "TRAVEL_MANAGER" | "TRAVELER" | "TRAVEL";
export type ReportStatus = "OPEN" | "IN_REVIEW" | "RESOLVED" | "DISMISSED";
export type ReportCategory = "FRAUD" | "HARASSMENT" | "SAFETY" | "MISLEADING_LISTING" | "INAPPROPRIATE_CONTENT" | "OTHER";

/** A report as its reporter sees it. */
export type Report = {
  reportId: string; targetType: ReportTargetType; targetId: string; targetDisplayName: string;
  category: ReportCategory; description: string; status: ReportStatus; createdAt: string;
};

export type ModerationEntry = { adminId: string; status: ReportStatus; note: string; at: string };

/** Admin-only view of a report, with the reporter and the moderation history. */
export type ReportDetail = Report & { reporterId: string; assigneeId: string | null; history: ModerationEntry[] };
