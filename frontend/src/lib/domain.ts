/** Frontend mirror of backend enums (wire format = Java enum names) + display labels. */

export type RoomType = "PRIVATE" | "SHARED" | "ENTIRE";
export type Furnishing = "UNFURNISHED" | "SEMI_FURNISHED" | "FULLY_FURNISHED";
export type ListingType =
  | "ENTIRE_APARTMENT"
  | "PRIVATE_ROOM"
  | "SHARED_ROOM"
  | "LOOKING_FOR_FLATMATE"
  | "REPLACEMENT";
export type ListingStatus = "DRAFT" | "ACTIVE" | "PAUSED" | "RENTED" | "EXPIRED" | "REMOVED";
export type Gender = "MALE" | "FEMALE" | "NON_BINARY" | "PREFER_NOT_TO_SAY";
export type GenderPreference = "MALE_ONLY" | "FEMALE_ONLY" | "ANY";
export type SmokingHabit = "NEVER" | "OCCASIONALLY" | "REGULARLY";
export type DrinkingHabit = "NEVER" | "SOCIALLY" | "REGULARLY";
export type Diet = "VEGETARIAN" | "EGGETARIAN" | "NON_VEGETARIAN" | "VEGAN" | "JAIN";
export type PetsStance = "HAS_PETS" | "LOVES_PETS" | "OK_WITH_PETS" | "NO_PETS";
export type SleepSchedule = "EARLY_BIRD" | "FLEXIBLE" | "NIGHT_OWL";
export type WfhFrequency = "NEVER" | "HYBRID" | "FULL_TIME";
export type CleanlinessLevel = "RELAXED" | "AVERAGE" | "VERY_TIDY";
export type SocialStyle = "VERY_SOCIAL" | "BALANCED" | "QUIET";
export type OccupationType =
  | "STUDENT"
  | "WORKING_PROFESSIONAL"
  | "FREELANCER"
  | "BUSINESS_OWNER"
  | "OTHER";

export interface Locality {
  id: string;
  name: string;
  city: string;
  lat: number;
  lng: number;
}

export interface AmenityRef {
  id: string;
  slug: string;
  label: string;
  category: string | null;
}

export const LABELS = {
  roomType: { PRIVATE: "Private room", SHARED: "Shared room", ENTIRE: "Entire place" },
  furnishing: {
    UNFURNISHED: "Unfurnished",
    SEMI_FURNISHED: "Semi-furnished",
    FULLY_FURNISHED: "Fully furnished",
  },
  listingType: {
    ENTIRE_APARTMENT: "Entire apartment",
    PRIVATE_ROOM: "Private room",
    SHARED_ROOM: "Shared room",
    LOOKING_FOR_FLATMATE: "Looking for flatmate",
    REPLACEMENT: "Replacement flatmate",
  },
  gender: {
    MALE: "Male",
    FEMALE: "Female",
    NON_BINARY: "Non-binary",
    PREFER_NOT_TO_SAY: "Prefer not to say",
  },
  genderPreference: { MALE_ONLY: "Male only", FEMALE_ONLY: "Female only", ANY: "Anyone" },
  smoking: { NEVER: "Never", OCCASIONALLY: "Occasionally", REGULARLY: "Regularly" },
  drinking: { NEVER: "Never", SOCIALLY: "Socially", REGULARLY: "Regularly" },
  diet: {
    VEGETARIAN: "Vegetarian",
    EGGETARIAN: "Eggetarian",
    NON_VEGETARIAN: "Non-vegetarian",
    VEGAN: "Vegan",
    JAIN: "Jain",
  },
  pets: {
    HAS_PETS: "Have pets",
    LOVES_PETS: "Love pets",
    OK_WITH_PETS: "Okay with pets",
    NO_PETS: "No pets please",
  },
  sleep: { EARLY_BIRD: "Early bird", FLEXIBLE: "Flexible", NIGHT_OWL: "Night owl" },
  wfh: { NEVER: "Office always", HYBRID: "Hybrid", FULL_TIME: "WFH full-time" },
  cleanliness: { RELAXED: "Relaxed", AVERAGE: "Average", VERY_TIDY: "Spotless" },
  social: { VERY_SOCIAL: "Love hosting", BALANCED: "Balanced", QUIET: "Keep to myself" },
  occupation: {
    STUDENT: "Student",
    WORKING_PROFESSIONAL: "Working professional",
    FREELANCER: "Freelancer",
    BUSINESS_OWNER: "Business owner",
    OTHER: "Other",
  },
} as const;

/** Chat-style relative timestamps: today → "10:42 AM", yesterday, weekday within 7 days, else date. */
export function formatRelativeTime(iso: string): string {
  const then = new Date(iso);
  const now = new Date();
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const dayDiff = Math.round((startOfDay(now) - startOfDay(then)) / 86_400_000);
  if (dayDiff <= 0) {
    return then.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });
  }
  if (dayDiff === 1) {
    return "Yesterday";
  }
  if (dayDiff < 7) {
    return then.toLocaleDateString("en-IN", { weekday: "short" });
  }
  return then.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}

export function formatINR(amount: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(amount);
}
