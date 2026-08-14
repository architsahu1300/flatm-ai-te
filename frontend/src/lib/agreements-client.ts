import { apiFetch, apiPost } from "@/lib/api";

export interface Clause {
  id: string;
  title: string;
  body: string;
  source: "standard" | "ai" | "custom";
}

export interface Signature {
  userId: string;
  name: string;
  role: "landlord" | "tenant";
  status: "PENDING" | "SIGNED";
  signedAt: string | null;
}

export type AgreementStatus = "DRAFT" | "UNDER_REVIEW" | "FINALIZED" | "SIGNED" | "CANCELLED";

export interface Agreement {
  id: string;
  status: AgreementStatus;
  landlordId: string;
  landlordName: string;
  signatures: Signature[];
  listingId: string | null;
  listingTitle: string | null;
  propertyAddress: string | null;
  rentMonthly: number;
  deposit: number;
  durationMonths: number;
  noticePeriodDays: number;
  lockInMonths: number;
  annualEscalationPct: number;
  startDate: string;
  agreementState: string;
  clauses: Clause[];
  currentVersion: number;
  viewerCanFinalize: boolean;
  viewerCanSign: boolean;
  createdAt: string;
  updatedAt: string;
}

export function fetchAgreements() {
  return apiFetch<Agreement[]>("/api/v1/agreements");
}

export function fetchAgreement(id: string) {
  return apiFetch<Agreement>(`/api/v1/agreements/${id}`);
}

export function fetchStandardClauses() {
  return apiFetch<Clause[]>("/api/v1/agreements/standard-clauses");
}

export function createAgreement(body: Record<string, unknown>) {
  return apiPost<Agreement>("/api/v1/agreements", body);
}

export function updateAgreement(id: string, body: Record<string, unknown>) {
  return apiFetch<Agreement>(`/api/v1/agreements/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

export function suggestClauses(id: string, context?: string) {
  return apiPost<Clause[]>(`/api/v1/agreements/${id}/clauses/suggest`, { context });
}

export function finalizeAgreement(id: string) {
  return apiPost<Agreement>(`/api/v1/agreements/${id}/finalize`, {});
}

export function signAgreement(id: string) {
  return apiPost<Agreement>(`/api/v1/agreements/${id}/sign`, {});
}

export function cancelAgreement(id: string) {
  return apiPost<Agreement>(`/api/v1/agreements/${id}/cancel`, {});
}
