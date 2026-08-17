import { apiFetch, apiPost } from "@/lib/api";

export type ReportReason =
  | "SCAM"
  | "FAKE_LISTING"
  | "HARASSMENT"
  | "INAPPROPRIATE_CONTENT"
  | "SPAM"
  | "OTHER";

export const REPORT_REASONS: { value: ReportReason; label: string }[] = [
  { value: "SCAM", label: "Scam / asked for money" },
  { value: "FAKE_LISTING", label: "Fake listing" },
  { value: "HARASSMENT", label: "Harassment" },
  { value: "INAPPROPRIATE_CONTENT", label: "Inappropriate content" },
  { value: "SPAM", label: "Spam" },
  { value: "OTHER", label: "Something else" },
];

export function submitReport(body: {
  reportedUserId?: string;
  reportedListingId?: string;
  reason: ReportReason;
  details?: string;
}) {
  return apiPost<{ id: string; status: string }>("/api/v1/reports", body);
}

export type VerificationType = "PHONE" | "EMAIL" | "GOV_ID" | "SELFIE" | "PROPERTY";

export interface Verification {
  id: string;
  type: VerificationType;
  status: "UNVERIFIED" | "PENDING" | "VERIFIED" | "REJECTED";
  createdAt: string;
}

export function fetchMyVerifications() {
  return apiFetch<Verification[]>("/api/v1/me/verifications");
}

export function requestVerification(type: VerificationType) {
  return apiPost<Verification>("/api/v1/me/verifications", { type });
}
