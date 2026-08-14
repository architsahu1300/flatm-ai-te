import {
  parseAsArrayOf,
  parseAsBoolean,
  parseAsInteger,
  parseAsString,
  parseAsStringLiteral,
} from "nuqs";

/** One vocabulary: these params feed both /explore and (later) the AI chips bridge. */
export const exploreParsers = {
  loc: parseAsArrayOf(parseAsString).withDefault([]),
  bmin: parseAsInteger,
  bmax: parseAsInteger,
  room: parseAsStringLiteral(["PRIVATE", "SHARED", "ENTIRE"] as const),
  furn: parseAsArrayOf(
    parseAsStringLiteral(["UNFURNISHED", "SEMI_FURNISHED", "FULLY_FURNISHED"] as const),
  ).withDefault([]),
  bhkMin: parseAsInteger,
  bhkMax: parseAsInteger,
  moveInBy: parseAsString,
  gender: parseAsStringLiteral(["MALE_ONLY", "FEMALE_ONLY", "ANY"] as const),
  amen: parseAsArrayOf(parseAsString).withDefault([]),
  verified: parseAsBoolean.withDefault(false),
  smokeFree: parseAsBoolean.withDefault(false),
  petFriendly: parseAsBoolean.withDefault(false),
  veg: parseAsBoolean.withDefault(false),
  social: parseAsStringLiteral(["QUIET", "BALANCED", "VERY_SOCIAL"] as const),
  sort: parseAsStringLiteral(["newest", "price_asc", "price_desc"] as const).withDefault("newest"),
};

export type ExploreFilters = {
  loc: string[];
  bmin: number | null;
  bmax: number | null;
  room: "PRIVATE" | "SHARED" | "ENTIRE" | null;
  furn: ("UNFURNISHED" | "SEMI_FURNISHED" | "FULLY_FURNISHED")[];
  bhkMin: number | null;
  bhkMax: number | null;
  moveInBy: string | null;
  gender: "MALE_ONLY" | "FEMALE_ONLY" | "ANY" | null;
  amen: string[];
  verified: boolean;
  smokeFree: boolean;
  petFriendly: boolean;
  veg: boolean;
  social: "QUIET" | "BALANCED" | "VERY_SOCIAL" | null;
  sort: "newest" | "price_asc" | "price_desc";
};

export function filtersToSearchParams(f: ExploreFilters, page: number, size: number): URLSearchParams {
  const p = new URLSearchParams();
  if (f.loc.length) p.set("loc", f.loc.join(","));
  if (f.bmin != null) p.set("bmin", String(f.bmin));
  if (f.bmax != null) p.set("bmax", String(f.bmax));
  if (f.room) p.set("room", f.room);
  if (f.furn.length) p.set("furn", f.furn.join(","));
  if (f.bhkMin != null) p.set("bhkMin", String(f.bhkMin));
  if (f.bhkMax != null) p.set("bhkMax", String(f.bhkMax));
  if (f.moveInBy) p.set("moveInBy", f.moveInBy);
  if (f.gender) p.set("gender", f.gender);
  if (f.amen.length) p.set("amen", f.amen.join(","));
  if (f.verified) p.set("verified", "true");
  if (f.smokeFree) p.set("smokeFree", "true");
  if (f.petFriendly) p.set("petFriendly", "true");
  if (f.veg) p.set("veg", "true");
  if (f.social) p.set("social", f.social);
  p.set("sort", f.sort);
  p.set("page", String(page));
  p.set("size", String(size));
  return p;
}

export function countActiveFilters(f: ExploreFilters): number {
  let n = 0;
  if (f.loc.length) n++;
  if (f.bmin != null || f.bmax != null) n++;
  if (f.room) n++;
  if (f.furn.length) n++;
  if (f.bhkMin != null || f.bhkMax != null) n++;
  if (f.moveInBy) n++;
  if (f.gender) n++;
  if (f.amen.length) n++;
  if (f.verified) n++;
  if (f.smokeFree) n++;
  if (f.petFriendly) n++;
  if (f.veg) n++;
  if (f.social) n++;
  return n;
}
