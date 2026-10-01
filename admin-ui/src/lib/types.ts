export type User = {
  id: string; email: string; firstName: string; lastName: string;
  role: "ADMIN" | "OPERATOR"; status: "ACTIVE" | "DISABLED" | "PENDING" | "FAILED";
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
