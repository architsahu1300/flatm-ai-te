import { track } from "@/lib/analytics";
import { apiPost } from "@/lib/api";
import type { FlatmateCard } from "@/lib/flatmates-client";
import type { ListingCard } from "@/lib/listings-client";

// ---- SearchIntent wire mirror (nulls = unspecified) ----

export interface SearchIntent {
  searchTarget?: "PROPERTIES" | "FLATMATES" | "BOTH" | null;
  locations?: { name: string; localityId: string | null }[] | null;
  budgetMin?: number | null;
  budgetMax?: number | null;
  roomType?: "PRIVATE" | "SHARED" | "ENTIRE" | null;
  listingTypes?: string[] | null;
  furnished?: "UNFURNISHED" | "SEMI_FURNISHED" | "FULLY_FURNISHED" | null;
  bhk?: { min: number | null; max: number | null } | null;
  moveInDate?: string | null;
  leaseMonths?: number | null;
  maxDeposit?: number | null;
  genderPreference?: "MALE_ONLY" | "FEMALE_ONLY" | "ANY" | null;
  couplesOk?: boolean | null;
  amenities?: string[] | null;
  lifestyle?: {
    quiet?: boolean | null;
    smoking?: string | null;
    pets?: string | null;
    diet?: string | null;
    drinking?: string | null;
    sleepSchedule?: string | null;
    cleanliness?: string | null;
    wfh?: boolean | null;
    partiesOk?: boolean | null;
  } | null;
  commuteTo?: { place: string; localityId: string | null; maxMinutes: number | null } | null;
  excludeLocations?: { name: string; localityId: string | null }[] | null;
  unresolvedLocations?: string[] | null;
  verifiedOnly?: boolean | null;
  confidence?: Record<string, number> | null;
  freeText?: string | null;
  originalQuery?: string | null;
}

export interface ScoreComponent {
  component: string;
  weight: number;
  score: number;
  detail: string | null;
}

export interface AiResult {
  kind: "home" | "flatmate";
  matchScore: number;
  scoreBreakdown: ScoreComponent[];
  matchReasons: string[];
  concerns: string[];
  commuteMinutes: number | null;
  commuteLabel: string | null;
  home: ListingCard | null;
  flatmate: FlatmateCard | null;
  nearMiss?: boolean | null;
  nearMissReason?: string | null;
}

export interface Relaxer {
  label: string;
  description: string;
  relaxedIntent: SearchIntent;
  extraResults: number;
}

export interface AiSearchResponse {
  sessionId: string;
  intent: SearchIntent;
  providerMode: "openai" | "mock";
  homes: AiResult[];
  flatmates: AiResult[];
  relaxers: Relaxer[];
  note: string | null;
}

export interface CompareRow {
  label: string;
  values: string[];
  bestIndex: number | null;
}

export interface CompareResponse {
  items: AiResult[];
  rows: CompareRow[];
  summary: string;
}

export function aiSearch(query: string, sessionId?: string | null) {
  track("ai_search", { queryLength: query.length });
  return apiPost<AiSearchResponse>("/api/v1/ai/search", { query, sessionId: sessionId ?? null });
}

export function aiRefine(query: string, sessionId: string) {
  track("search_refined", { queryLength: query.length });
  return apiPost<AiSearchResponse>("/api/v1/ai/refine", { query, sessionId });
}

export function aiApply(intent: SearchIntent, sessionId: string) {
  return apiPost<AiSearchResponse>("/api/v1/ai/apply", { intent, sessionId });
}

export function aiCompare(candidateIds: string[], sessionId: string) {
  return apiPost<CompareResponse>("/api/v1/ai/compare", { candidateIds, sessionId });
}

// ---- Chips: a pure projection of the intent, each with a removal transform ----

export interface IntentChip {
  key: string;
  icon: string;
  label: string;
  value: string;
  soft?: boolean;
  remove: (intent: SearchIntent) => SearchIntent;
}

const HARD_THRESHOLD = 0.75;
const ALWAYS_HARD = new Set(["excludeLocations", "verifiedOnly"]);

/** A slot the reader inferred rather than read: it ranks results, it does not filter them. */
export function isSoft(intent: SearchIntent, slot: string): boolean {
  if (ALWAYS_HARD.has(slot)) return false;
  return (intent.confidence?.[slot] ?? 1) < HARD_THRESHOLD;
}

const inr = (n: number) => `₹${n.toLocaleString("en-IN")}`;

/** Applies the ≈-prefix + soft flag to a chip's value when its backing slot is a preference, not a filter. */
const withSoftness = (intent: SearchIntent, slot: string, value: string): { value: string; soft?: boolean } =>
  isSoft(intent, slot) ? { value: `≈ ${value}`, soft: true } : { value };

export function chipsFromIntent(intent: SearchIntent): IntentChip[] {
  const chips: IntentChip[] = [];
  const target = intent.searchTarget ?? "PROPERTIES";

  chips.push({
    key: "target",
    icon: target === "FLATMATES" ? "🤝" : target === "BOTH" ? "✦" : "🏠",
    label: "Looking for",
    value: target === "FLATMATES" ? "Flatmates" : target === "BOTH" ? "Homes + people" : "Homes",
    remove: (i) => ({ ...i, searchTarget: target === "FLATMATES" ? "PROPERTIES" : "FLATMATES" }),
  });

  for (const loc of intent.locations ?? []) {
    chips.push({
      key: `loc:${loc.name}`,
      icon: "📍",
      label: "Location",
      ...withSoftness(intent, "locations", loc.name),
      remove: (i) => ({
        ...i,
        locations: (i.locations ?? []).filter((l) => l.name !== loc.name),
      }),
    });
  }
  for (const loc of intent.excludeLocations ?? []) {
    chips.push({
      key: `excl:${loc.name}`,
      icon: "🚫",
      label: "Not in",
      value: loc.name,
      remove: (i) => ({
        ...i,
        excludeLocations: (i.excludeLocations ?? []).filter((l) => l.name !== loc.name),
      }),
    });
  }
  for (const name of intent.unresolvedLocations ?? []) {
    chips.push({
      key: `unres:${name}`,
      icon: "📍?",
      label: "Couldn't place",
      value: name,
      remove: (i) => ({
        ...i,
        unresolvedLocations: (i.unresolvedLocations ?? []).filter((n) => n !== name),
      }),
    });
  }
  if (intent.commuteTo) {
    chips.push({
      key: "commute",
      icon: "🚇",
      label: "Commute",
      ...withSoftness(intent, "commuteTo", `≤${intent.commuteTo.maxMinutes ?? 30} min to ${intent.commuteTo.place}`),
      remove: (i) => ({ ...i, commuteTo: null }),
    });
  }
  if (intent.budgetMax != null || intent.budgetMin != null) {
    const value =
      intent.budgetMax != null && intent.budgetMin != null
        ? `${inr(intent.budgetMin)} – ${inr(intent.budgetMax)}`
        : intent.budgetMax != null
          ? `≤ ${inr(intent.budgetMax)}`
          : `≥ ${inr(intent.budgetMin!)}`;
    chips.push({
      key: "budget",
      icon: "💰",
      label: "Budget",
      ...withSoftness(intent, "budgetMax", value),
      remove: (i) => ({ ...i, budgetMax: null, budgetMin: null }),
    });
  }
  if (intent.maxDeposit != null) {
    chips.push({
      key: "deposit",
      icon: "🔐",
      label: "Deposit",
      ...withSoftness(intent, "maxDeposit", `≤ ${inr(intent.maxDeposit)}`),
      remove: (i) => ({ ...i, maxDeposit: null }),
    });
  }
  if (intent.roomType) {
    const labels = { PRIVATE: "Private room", SHARED: "Shared room", ENTIRE: "Entire place" };
    chips.push({
      key: "room",
      icon: "🛏",
      label: "Room",
      ...withSoftness(intent, "roomType", labels[intent.roomType]),
      remove: (i) => ({ ...i, roomType: null }),
    });
  }
  if (intent.bhk?.min != null || intent.bhk?.max != null) {
    const v =
      intent.bhk.min === intent.bhk.max
        ? `${intent.bhk.min} BHK`
        : `${intent.bhk.min ?? "?"}–${intent.bhk.max ?? "?"} BHK`;
    chips.push({ key: "bhk", icon: "🏢", label: "Size", ...withSoftness(intent, "bhk", v), remove: (i) => ({ ...i, bhk: null }) });
  }
  if (intent.furnished) {
    const labels = {
      UNFURNISHED: "Unfurnished",
      SEMI_FURNISHED: "Semi-furnished",
      FULLY_FURNISHED: "Furnished",
    };
    chips.push({
      key: "furnished",
      icon: "🛋",
      label: "Furnishing",
      ...withSoftness(intent, "furnished", labels[intent.furnished]),
      remove: (i) => ({ ...i, furnished: null }),
    });
  }
  if (intent.moveInDate) {
    chips.push({
      key: "movein",
      icon: "📅",
      label: "Move-in",
      ...withSoftness(
        intent,
        "moveInDate",
        new Date(intent.moveInDate).toLocaleDateString("en-IN", { day: "numeric", month: "short" }),
      ),
      remove: (i) => ({ ...i, moveInDate: null }),
    });
  }
  const l = intent.lifestyle ?? {};
  const lifestyleChip = (key: string, icon: string, value: string, patch: Partial<NonNullable<SearchIntent["lifestyle"]>>) =>
    chips.push({
      key: `life:${key}`,
      icon,
      label: "Lifestyle",
      value,
      remove: (i) => ({ ...i, lifestyle: { ...(i.lifestyle ?? {}), ...patch } }),
    });
  if (l.quiet) lifestyleChip("quiet", "🤫", "Quiet home", { quiet: null });
  if (l.smoking === "NO_SMOKERS") lifestyleChip("smoking", "🚭", "No smokers", { smoking: null });
  if (l.diet === "VEGETARIAN") lifestyleChip("diet", "🥗", "Vegetarian", { diet: null });
  if (l.pets === "NO_PETS") lifestyleChip("pets", "🚫🐾", "No pets", { pets: null });
  if (l.pets === "PET_FRIENDLY" || l.pets === "HAS_PETS") lifestyleChip("pets", "🐾", "Pet friendly", { pets: null });
  if (l.wfh) lifestyleChip("wfh", "💻", "Works from home", { wfh: null });
  if (l.partiesOk === false) lifestyleChip("parties", "🔕", "No party house", { partiesOk: null });
  if (intent.genderPreference && intent.genderPreference !== "ANY") {
    chips.push({
      key: "gender",
      icon: "👤",
      label: "Preference",
      ...withSoftness(
        intent,
        "genderPreference",
        intent.genderPreference === "FEMALE_ONLY" ? "Female only" : "Male only",
      ),
      remove: (i) => ({ ...i, genderPreference: null }),
    });
  }
  if (intent.verifiedOnly) {
    chips.push({
      key: "verified",
      icon: "✅",
      label: "Trust",
      value: "Verified only",
      remove: (i) => ({ ...i, verifiedOnly: null }),
    });
  }
  return chips;
}
