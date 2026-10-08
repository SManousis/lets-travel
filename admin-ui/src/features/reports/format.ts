import { ApiError, errorMessage } from "../../lib/api";
import type { ReportCategory, ReportStatus, ReportTargetType } from "../../lib/types";

export const categoryLabels: Record<ReportCategory, string> = {
  FRAUD: "Fraud or scam",
  HARASSMENT: "Harassment",
  SAFETY: "Safety concern",
  MISLEADING_LISTING: "Misleading listing",
  INAPPROPRIATE_CONTENT: "Inappropriate content",
  OTHER: "Something else",
};

export const statusLabels: Record<ReportStatus, string> = {
  OPEN: "Open",
  IN_REVIEW: "In review",
  RESOLVED: "Upheld",
  DISMISSED: "Dismissed",
};

export const targetLabels: Record<ReportTargetType, string> = {
  TRAVEL_MANAGER: "Travel manager",
  TRAVELER: "Traveler",
  TRAVEL: "Travel",
};

/** RESOLVED and DISMISSED are final; engagement-service refuses further changes. */
export function isClosed(status: ReportStatus): boolean {
  return status === "RESOLVED" || status === "DISMISSED";
}

/** Plain-language messages for the report rules enforced by engagement-service. */
export function reportErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "SELF_REPORT": return "You cannot report yourself or your own travel.";
      case "DUPLICATE_REPORT": return "You already have an open report about this. An administrator will review it.";
      case "TOO_MANY_REPORTS": return "You have sent many reports today. Try again tomorrow.";
      case "REPORT_CLOSED": return "This report is already closed and cannot be changed.";
      case "NOT_FOUND": return "We could not find what you are reporting.";
    }
  }
  return errorMessage(error);
}
