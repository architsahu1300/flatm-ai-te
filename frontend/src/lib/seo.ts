/**
 * Server-only helpers for the SEO landing pages. These fetch public backend data
 * with time-based revalidation (the pages carry no viewer state), unlike serverFetch
 * which is per-request and cookie-forwarding.
 */

import { BACKEND_URL } from "@/lib/api";
import type { Locality } from "@/lib/domain";
import type { ListingCard } from "@/lib/listings-client";

export const SEO_REVALIDATE_SECONDS = 3600;

export function slugify(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "");
}

async function cachedGet<T>(path: string): Promise<T> {
  const res = await fetch(`${BACKEND_URL}${path}`, {
    next: { revalidate: SEO_REVALIDATE_SECONDS },
  });
  if (!res.ok) {
    throw new Error(`SEO fetch failed: ${path} → ${res.status}`);
  }
  const body = (await res.json()) as { data: T };
  return body.data;
}

export function fetchLocalitiesCached(): Promise<Locality[]> {
  return cachedGet<Locality[]>("/api/v1/localities");
}

export async function findLocalityBySlug(slug: string): Promise<Locality | null> {
  const all = await fetchLocalitiesCached();
  return all.find((l) => slugify(l.name) === slug) ?? null;
}

export interface LocalityStats {
  byRoomType: { room_type: string; listings: number; median_rent: number }[];
  activeFlatmates: number;
}

export function fetchLocalityStats(localityId: string): Promise<LocalityStats> {
  return cachedGet<LocalityStats>(`/api/v1/localities/${localityId}/stats`);
}

export interface ListingPage {
  items: ListingCard[];
  total: number;
}

export function fetchListingsCached(params: {
  localityId?: string;
  size?: number;
  sort?: string;
}): Promise<ListingPage> {
  const qs = new URLSearchParams({ size: String(params.size ?? 9), sort: params.sort ?? "newest" });
  if (params.localityId) qs.set("loc", params.localityId);
  return cachedGet<ListingPage>(`/api/v1/listings?${qs}`);
}

export const ROOM_TYPE_SEO_LABEL: Record<string, string> = {
  PRIVATE: "Private room",
  SHARED: "Shared room",
  ENTIRE: "Entire flat",
};
