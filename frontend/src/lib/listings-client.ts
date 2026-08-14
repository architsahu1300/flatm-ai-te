import { apiFetch } from "@/lib/api";
import type {
  Diet,
  Furnishing,
  GenderPreference,
  ListingStatus,
  ListingType,
  RoomType,
  SocialStyle,
} from "@/lib/domain";

export interface ListingCard {
  id: string;
  type: ListingType;
  status: ListingStatus;
  title: string;
  rentMonthly: number;
  deposit: number;
  maintenanceMonthly: number;
  localityName: string | null;
  localityId: string | null;
  bhk: number | null;
  roomType: RoomType;
  furnishing: Furnishing;
  availableFrom: string;
  coverImageUrl: string | null;
  amenitySlugs: string[];
  preferredGender: GenderPreference;
  householdSocial: SocialStyle | null;
  householdSmoking: boolean | null;
  householdPets: boolean | null;
  householdDiet: Diet | null;
  occupantsDesc: string | null;
  listerVerified: boolean;
  propertyVerified: boolean;
  isBoosted: boolean;
  updatedAt: string;
}

export interface ListingImage {
  id: string;
  url: string;
  isCover: boolean;
  sortOrder: number;
}

export interface ListingDetail {
  card: ListingCard;
  description: string;
  minLeaseMonths: number | null;
  maxOccupants: number | null;
  bathroomAttached: boolean | null;
  balcony: boolean | null;
  couplesAllowed: boolean | null;
  images: ListingImage[];
  amenityLabels: string[];
  approxLat: number | null;
  approxLng: number | null;
  listerId: string;
  listerName: string | null;
  listerImage: string | null;
  viewCount: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export function fetchListings(params: URLSearchParams): Promise<Page<ListingCard>> {
  return apiFetch<Page<ListingCard>>(`/api/v1/listings?${params.toString()}`);
}

export function fetchMyListings(): Promise<ListingCard[]> {
  return apiFetch<ListingCard[]>("/api/v1/me/listings");
}

export function createListing(body: Record<string, unknown>) {
  return apiFetch<{ id: string; status: ListingStatus }>("/api/v1/listings", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function updateListing(id: string, body: Record<string, unknown>) {
  return apiFetch<ListingDetail>(`/api/v1/listings/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

export function changeListingStatus(id: string, status: ListingStatus) {
  return apiFetch<{ id: string; status: ListingStatus }>(`/api/v1/listings/${id}/status`, {
    method: "POST",
    body: JSON.stringify({ status }),
  });
}

export async function uploadListingImage(id: string, file: File): Promise<ListingImage[]> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`/api/v1/listings/${id}/images`, { method: "POST", body: form });
  const body = await res.json();
  if (!res.ok) throw new Error(body?.error?.message ?? "Upload failed");
  return body.data as ListingImage[];
}

export function deleteListingImage(id: string, imageId: string) {
  return apiFetch<ListingImage[]>(`/api/v1/listings/${id}/images/${imageId}`, { method: "DELETE" });
}
