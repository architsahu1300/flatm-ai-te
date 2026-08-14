import { apiFetch } from "@/lib/api";

export interface FlatmateCard {
  id: string;
  userId: string;
  name: string;
  image: string | null;
  age: number | null;
  gender: string | null;
  occupation: string | null;
  occupationDetail: string | null;
  headline: string;
  hasFlat: boolean;
  budgetMin: number | null;
  budgetMax: number | null;
  localityNames: string[];
  moveInFrom: string | null;
  lifestyleTags: string[];
  idVerified: boolean;
  compatibility: number | null;
  sharedTraits: string[];
}

export interface FlatmateDetail {
  card: FlatmateCard;
  about: string;
  bio: string | null;
  companyOrCollege: string | null;
  languages: string[];
  smoking: string | null;
  drinking: string | null;
  diet: string | null;
  pets: string | null;
  sleepSchedule: string | null;
  wfhFrequency: string | null;
  cleanliness: string | null;
  socialStyle: string | null;
  partyFrequency: string | null;
  guestFrequency: string | null;
  cookingFrequency: string | null;
}

export function fetchFlatmates(params: URLSearchParams) {
  return apiFetch<FlatmateCard[]>(`/api/v1/flatmates?${params.toString()}`);
}

export function fetchFlatmate(id: string) {
  return apiFetch<FlatmateDetail>(`/api/v1/flatmates/${id}`);
}

export function upsertMyFlatmateProfile(body: Record<string, unknown>) {
  return apiFetch("/api/v1/me/flatmate-profile", { method: "PUT", body: JSON.stringify(body) });
}

export function getMyFlatmateProfile() {
  return apiFetch<Record<string, unknown>>("/api/v1/me/flatmate-profile");
}

export function setFlatmateActive(active: boolean) {
  return apiFetch(`/api/v1/me/flatmate-profile/${active ? "activate" : "deactivate"}`, {
    method: "POST",
    body: "{}",
  });
}
