import { apiFetch, apiPost } from "@/lib/api";

/**
 * Admin API client. Rows come straight from SQL aggregates, so keys are
 * snake_case column aliases rather than the camelCase used elsewhere.
 */

export interface AdminStats {
  counts: {
    users: number;
    suspended_users: number;
    active_listings: number;
    suspicious_listings: number;
    flatmate_cards: number;
    conversations: number;
    messages: number;
    open_reports: number;
    pending_verifications: number;
    agreements: number;
    signed_agreements: number;
    saved_searches: number;
  };
  funnel: {
    ai_searches: number;
    search_sessions: number;
    contacts: number;
    connections: number;
    agreements: number;
  };
  aiToday: { calls: number; cost_usd: number };
}

export interface AdminUser {
  id: string;
  name: string;
  email: string | null;
  phone: string | null;
  role: "USER" | "ADMIN";
  is_suspended: boolean;
  created_at: string;
  last_active_at: string | null;
  listing_count: number;
  report_count: number;
}

export interface AdminListing {
  id: string;
  title: string;
  status: string;
  rent_monthly: number;
  scam_risk_score: number;
  view_count: number;
  created_at: string;
  lister_name: string;
  lister_id: string;
  locality: string | null;
  open_reports: number;
}

export type AdminReportStatus = "OPEN" | "UNDER_REVIEW" | "RESOLVED" | "DISMISSED";

export interface AdminReport {
  id: string;
  reason: string;
  status: AdminReportStatus;
  details: string | null;
  created_at: string;
  resolution_note: string | null;
  reporter_name: string;
  reported_user_name: string | null;
  reported_user_id: string | null;
  reported_listing_title: string | null;
  reported_listing_id: string | null;
  scam_risk_score: number | null;
}

export interface AdminVerification {
  id: string;
  type: string;
  status: string;
  provider: string | null;
  created_at: string;
  user_name: string | null;
  email: string | null;
  user_id: string | null;
  property_id: string | null;
}

export interface AiUsage {
  byDay: {
    day: string;
    calls: number;
    prompt_tokens: number | null;
    completion_tokens: number | null;
    cost_usd: number;
    cache_hits: number;
  }[];
  byFeature: { feature: string; provider: string | null; calls: number; cost_usd: number }[];
  generatedAt: string;
}

export function fetchStats() {
  return apiFetch<AdminStats>("/api/v1/admin/stats");
}

export function fetchUsers(q: string, page = 0) {
  const params = new URLSearchParams({ page: String(page), size: "25" });
  if (q) params.set("q", q);
  return apiFetch<AdminUser[]>(`/api/v1/admin/users?${params}`);
}

export function setUserSuspended(id: string, suspended: boolean) {
  return apiFetch<{ id: string; suspended: boolean }>(`/api/v1/admin/users/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ suspended }),
  });
}

export function fetchListings(opts: { status?: string; suspicious?: boolean; page?: number }) {
  const params = new URLSearchParams({ page: String(opts.page ?? 0), size: "25" });
  if (opts.status) params.set("status", opts.status);
  if (opts.suspicious) params.set("suspicious", "true");
  return apiFetch<AdminListing[]>(`/api/v1/admin/listings?${params}`);
}

export function removeListing(id: string) {
  return apiFetch<{ id: string; status: string }>(`/api/v1/admin/listings/${id}`, {
    method: "PATCH",
    body: JSON.stringify({}),
  });
}

export function rescoreListings() {
  return apiPost<{ recomputed: number }>("/api/v1/admin/listings/rescore", {});
}

export function fetchReports(status?: AdminReportStatus) {
  const qs = status ? `?status=${status}` : "";
  return apiFetch<AdminReport[]>(`/api/v1/admin/reports${qs}`);
}

export function resolveReport(id: string, status: AdminReportStatus, resolutionNote?: string) {
  return apiFetch<{ id: string; status: string }>(`/api/v1/admin/reports/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ status, resolutionNote }),
  });
}

export function fetchPendingVerifications() {
  return apiFetch<AdminVerification[]>("/api/v1/admin/verifications?status=PENDING");
}

export function reviewVerification(id: string, approve: boolean) {
  return apiFetch<{ id: string; status: string }>(`/api/v1/admin/verifications/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ approve }),
  });
}

export function fetchAiUsage() {
  return apiFetch<AiUsage>("/api/v1/admin/ai-usage");
}
