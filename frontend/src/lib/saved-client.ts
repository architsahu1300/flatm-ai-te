import { track } from "@/lib/analytics";
import { apiFetch, apiPost } from "@/lib/api";
import type { AiSearchResponse, SearchIntent } from "@/lib/ai-client";
import type { ListingCard } from "@/lib/listings-client";

export interface SavedSearch {
  id: string;
  name: string;
  intent: string; // serialized SearchIntent
  alertsEnabled: boolean;
  lastRunAt: string | null;
  lastResultCount: number | null;
  updatedAt: string;
}

export function fetchSavedListings() {
  return apiFetch<ListingCard[]>("/api/v1/saved-listings");
}

export function fetchSavedIds() {
  return apiFetch<string[]>("/api/v1/saved-listings/ids");
}

export function saveListing(listingId: string, note?: string) {
  track("listing_saved");
  return apiPost<{ saved: boolean }>("/api/v1/saved-listings", { listingId, note });
}

export function unsaveListing(listingId: string) {
  return apiFetch<{ saved: boolean }>(`/api/v1/saved-listings/${listingId}`, { method: "DELETE" });
}

export function fetchSavedSearches() {
  return apiFetch<SavedSearch[]>("/api/v1/saved-searches");
}

export function createSavedSearch(name: string, intent: SearchIntent, alertsEnabled = false) {
  track("search_saved");
  return apiPost<SavedSearch>("/api/v1/saved-searches", { name, intent, alertsEnabled });
}

export function updateSavedSearch(id: string, body: { name?: string; alertsEnabled?: boolean }) {
  return apiFetch<SavedSearch>(`/api/v1/saved-searches/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

export function deleteSavedSearch(id: string) {
  return apiFetch<{ deleted: boolean }>(`/api/v1/saved-searches/${id}`, { method: "DELETE" });
}

export function runSavedSearch(id: string) {
  return apiPost<AiSearchResponse>(`/api/v1/saved-searches/${id}/run`, {});
}
