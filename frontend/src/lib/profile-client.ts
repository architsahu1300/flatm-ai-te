import { apiFetch } from "@/lib/api";
import type { AmenityRef, Locality } from "@/lib/domain";

export function getLocalities() {
  return apiFetch<Locality[]>("/api/v1/localities");
}

export function getAmenities() {
  return apiFetch<AmenityRef[]>("/api/v1/amenities");
}

export function updateProfile(body: Record<string, unknown>) {
  return apiFetch("/api/v1/me/profile", { method: "PUT", body: JSON.stringify(body) });
}

export function updatePreferences(body: Record<string, unknown>) {
  return apiFetch("/api/v1/me/preferences", { method: "PUT", body: JSON.stringify(body) });
}
