# WS6 — Locality Distance & Fallback Ladder: design

Date: 2026-09-21 · Status: draft for review · Amends WS4 (`2026-09-15-confidence-gating-design.md` §4.4, §4.7)
and WS2 (`2026-09-15-query-understanding-design.md` §4.5). Taken before WS5 (image analysis) at the product
owner's request.

## 1. Problem

A user searched `"Single sharing room in Kandivali under 15K"` and got a ₹12,000 room in Kurla and a ₹14,500
room in Goregaon — both a long way from Kandivali — with a `📍? Couldn't place: Kandivali` chip above them.

The parse was correct:

```
locations           : null
unresolvedLocations : ["Kandivali"]
budgetMax           : 15000
roomType            : PRIVATE
```

Three separate weaknesses compounded:

1. **The gazetteer is the only way to place a name.** `LocalityResolver` matches query words against rows in
   `localities` and nothing else. Kandivali is absent from that table, so the name was unplaceable — not
   because Kandivali is obscure, but because the locality list is hand-curated.
2. **An unplaceable name silently deletes the constraint.** `HybridRetriever.admittedLocalityIds` returns an
   empty list, and `toFiltersWithRadius` reads empty as *no locality filter* (line 313). Budget and room type
   still applied, citywide. The user asked for one neighbourhood and was confidently served another, with only
   a chip to signal it.
3. **Distance is computed and then thrown away.** `CommuteEstimator` derives minutes from haversine kilometres
   (`roadKm / 20 km/h × 60 + 8 min`), but `Nearby` carries only `minutes`, and both rings
   (`nearbyRadiusMinutes = 25`, `rescueRadiusMinutes = 45`) are expressed in minutes. Nothing downstream can
   say "3.1 km away", and the 8-minute fixed overhead means minutes are a poor proxy at short range: 2 km reads
   as ~16 min, 5 km as ~29 min, so today's 25-minute ring is an accidental ~4 km.

There is also a fourth, quieter problem. The `× 1.1` budget headroom at `HybridRetriever:320` admits listings up
to 110% of the stated budget **into the primary result set**. A ₹16,500 room appears among "8 matches" for a
₹15,000 search, labelled by the scorer but not separated. The product owner's position is that a result block
titled as matching the user's budget must contain only listings within it.

## 2. Goals / non-goals

**Goals**

- Distance becomes a first-class quantity: kilometres at the core, minutes as a derived, labelled estimate.
- A search naming a locality never silently becomes a citywide search.
- When a locality is thin, fall back in a fixed, honest order: nearer first, then slightly over budget, then stop
  and say so.
- The user's stated budget is never changed by the system — only by an explicit act.
- Expand the seeded gazetteer so the common Mumbai localities resolve.

**Non-goals**

- Geocoding unknown names through an external provider. That is ladder steps 3–4 and has its own workstream
  (see §4.3); this spec must leave a clean seam for it but adds no network dependency and no API key.
- Polygon/boundary search. Rings around centroids and per-property points are enough at this scale.
- Replacing the commute estimate with a real Distance Matrix provider. The estimator's contract is unchanged.
- Any change to intent extraction. WS3's golden set must stay green untouched.

## 3. Decisions taken with the product owner

1. Results appear immediately; a question never blocks them. The compromise (a higher budget) is offered as a
   one-click choice alongside the results, not as a prompt the user must answer first.
2. Auto-shown over-budget listings **do not** change `SearchIntent.budgetMax`. Viewing such a listing does not
   either. Only clicking the explicit "Raise budget to ₹X" chip does, and that change is sticky for the session.
3. After an explicit budget escalation, the re-run searches within 5 km of the originally named locality.
4. Automatic relaxation is limited to two dimensions — distance, and a +10% budget band — both labelled. No
   other filter is dropped without the user clicking a relaxer. This amends WS4 §4.7, which dropped filters
   automatically by ascending confidence.
5. A query naming no locality still returns citywide results, with no headline and no fallback framing.
6. Unresolved names get the same fallback treatment with an explicit headline, rather than silent citywide.

## 4. Design

### 4.1 Distance as the primitive

`CommuteEstimator.Nearby` gains kilometres:

```java
public record Nearby(UUID localityId, double km, int minutes) {}
```

`minutes` stays exactly as computed today — same circuity, speed and overhead constants — so no existing
estimate changes. `km` is the haversine road-distance (`haversineKm × ROAD_CIRCUITY`) that the minute figure was
already derived from, now returned instead of discarded.

`nearestLocalities(UUID anchor, double maxKm, int limit)` replaces the minute-bounded overload; the
minute-bounded one is removed rather than kept alongside, so there is one way to express a ring.

**Per-property distance.** Locality centroids are too coarse for "within 2 km" when a locality spans several
kilometres. `properties.lat/lng` are already populated (50 of 51 seeded rows) and already selected by the
retrieval SQL (`HybridRetriever:85`, `:135`). A new migration adds the index to make radius filtering cheap:

```sql
-- V3__geo.sql
CREATE EXTENSION IF NOT EXISTS cube;
CREATE EXTENSION IF NOT EXISTS earthdistance;
CREATE INDEX idx_properties_geo ON properties USING gist (ll_to_earth(lat, lng));
```

Both extensions ship in `pgvector/pgvector:pg16`, which is the image used by Docker Compose *and* by the
Testcontainers integration tests, so no image change is needed. Properties with null coordinates fall back to
their locality centroid, so a missing lat/lng degrades to today's behaviour rather than dropping the listing.

**Configuration.** `FlatmaiteProperties.Search` moves to kilometres:

| Property | Env var | Default | Replaces |
|---|---|---|---|
| `nearbyRadiusKm` | `SEARCH_NEARBY_RADIUS_KM` | `5.0` | `nearbyRadiusMinutes` (25) |
| `closeRadiusKm` | `SEARCH_CLOSE_RADIUS_KM` | `2.0` | — (new; labelling threshold, §4.6) |
| `escalationRadiusKm` | `SEARCH_ESCALATION_RADIUS_KM` | `5.0` | `rescueRadiusMinutes` (45) |
| `minResults` | `SEARCH_MIN_RESULTS` | `6` | unchanged |

The two minute-based properties are removed, not deprecated in place — keeping both units invites drift. The
README environment table is updated in the same change.

### 4.2 Gazetteer expansion

`SeedLocalities.ALL` grows from 38 to 59 entries, adding the 21 prime areas a Mumbai renter is likely to name:

- **Western:** Versova, Oshiwara, Lokhandwala, Dahisar, Mira Road, Bhayandar
- **Central:** Byculla, Prabhadevi, Vidyavihar, Kalyan, Dombivli
- **South:** Churchgate, Marine Lines, Fort, Grant Road, Tardeo, Malabar Hill
- **Harbour / Navi Mumbai:** Nerul, Belapur, Ghansoli, Panvel

Each entry keeps the existing shape — name, lat, lng, aliases, `rentBand` — with coordinates taken from the
locality centroid and `rentBand` set to the typical private-room rent midpoint, consistent with the existing 38.

Listing volume scales with the gazetteer: `LISTING_COUNT` rises from 80 to 240. Locality assignment is already
round-robin (`locs.get(i % locs.size())` in `seedListings`), so ~4 listings per locality follow automatically —
no change to the assignment logic. `rentFor(rentBand, type, bhk)` must be checked to confirm it produces rents
both under and over a typical band midpoint, so every locality has something for each tier of §4.4 to find;
if it does not, its spread widens.

`USER_COUNT` rises to 120 so listers are not absurdly over-subscribed. `FLATMATE_COUNT` stays at 35.

Ids remain UUIDv3 over a stable key, so re-seeding upserts rather than duplicating, and existing rows survive.

### 4.3 Resolution ladder (steps 1–2 of 4)

`LocalityResolver` gains a second step, stopping at the first hit:

1. **Gazetteer** — exact, alias, longest-match, then trigram fuzzy ≥ 0.55. Unchanged.
2. **Own data** — match the token window against `properties.society_name` and `address_line`. A hit resolves to
   the centroid of the matching properties plus their locality ids. This is free, needs no network, and gets
   better as real listings arrive — "Hiranandani" resolves because listings say Hiranandani, not because someone
   curated it.

Step **3 (external geocoder)** belongs to the geocoding workstream. Step **4 (give up honestly)** is specified
here, in §4.8 — an unplaceable name must behave well today, with or without a geocoder. This spec defines the
seam: resolution returns a `Placement` carrying `localityIds`, an optional point, a `source`
(`GAZETTEER | OWN_DATA | GEOCODED | NONE`) and a confidence. A later geocoder adds a case without touching
callers. Until then, `source = NONE` drives §4.8.

`OWN_DATA` placements enter WS4's grounding as `INFERRED` (0.5), not `STATED`, so `ConfidenceGate` treats them
as a ranking preference rather than a hard filter — the existing machinery, unchanged.

### 4.4 The fallback ladder

Replaces `RescueLadder.rungs()`. Tiers run in order and are cumulative; the ladder stops as soon as the page
holds `minResults` rows.

| Tier | Contents | Row label |
|---|---|---|
| 1 | Requested placement, `rent ≤ budgetMax` | none — these are plain matches |
| 2 | Within `nearbyRadiusKm`, `rent ≤ budgetMax`, nearest first | "Borivali · 3.1 km from Kandivali" |
| 3 | Requested placement **and** within `nearbyRadiusKm`, `budgetMax < rent ≤ budgetMax × 1.1` | "₹16,500 — ₹1,500 over your budget" |
| 4 | Nothing more is added | terminus message + relaxer chips |

Every tier enforces **all other filters** — room type, furnishing, gender, amenities, exclusions — exactly as
tier 1 does. Distance and the +10% band are the only things that relax, and both are stated on the row.

This is the amendment to WS4 §4.7: the ladder no longer drops slots automatically by ascending confidence.
That capability does not disappear — it moves to `computeRelaxers`, which already offers counted, one-click
relaxations ("Raise budget to ₹18,000 — shows 6 options"). The governing principle from WS4 §4.4 survives in a
sharper form: **a filter is either enforced or visibly relaxed, never quietly dropped.**

`RescueLadder.without(...)` is retained for the relaxer path; only `rungs()` is replaced.

### 4.5 Budget semantics

The `× 1.1` headroom moves out of the base filter (`HybridRetriever:320`) and becomes tier 3's band. Tier 1 and
tier 2 use `rent ≤ budgetMax` exactly. A block presented as within budget contains only listings within it.

`SearchIntent.budgetMax` is immutable across the automatic path. Tiers 2 and 3 are presentation and retrieval
concerns; they never write back to the intent, so a follow-up refinement ("only verified ones") still carries
₹15,000. The only mutation is §4.7.

The flatmate retrieval cap (`× 1.2`, `HybridRetriever:172`) is out of scope and unchanged; it is a retrieval
breadth knob on a different path, not a displayed budget claim.

### 4.6 Telling the user

The pipeline already computes `exactCount`, `includesNearby`, `nearbyRadius` and `rescueSummary` on
`SearchPipeline.Homes` and then discards them — the API returns a bare `homes: [...]` plus a single `note`
string. They become part of the contract:

```
homes[].tier            EXACT | NEARBY | OVER_BUDGET
homes[].distanceKm      distance from the placement anchor (null when no locality was named)
homes[].minutesFromAnchor
homes[].anchorName      "Kandivali"
resultSummary {
  anchorName, exactCount, nearbyCount, overBudgetCount,
  headline,        // "No listings in Kandivali under ₹15,000."
  terminus         // "No more listings within ₹15,000 near Kandivali." — null unless tier 4 reached
}
choices[] {          // counted, one-click; empty when nothing sensible to offer
  label,             // "Kandivali has 3 from ₹17,000"
  action,            // RAISE_BUDGET
  value,             // 17000
  count              // 3
}
```

`choices` are computed with the existing `countFor` / `cheapestRentFor` helpers: the in-locality option takes the
cheapest rent above budget that still satisfies every other filter, rounded up to ₹500, with its count.

The frontend replaces the flat `{results.length} matches, best first`
([search-screen.tsx:199](../../../frontend/src/app/(app)/search/search-screen.tsx)) with headline, then grouped
sections in tier order, each with its own subhead ("Within 5 km, under budget" / "In Kandivali, slightly over
budget"), then the terminus line and the choice chips. `AiMatchCard` gains a distance chip; rows within
`closeRadiusKm` read "very close" rather than a bare figure. The existing per-row near-miss badge is reused.

### 4.7 Budget escalation

Clicking a `RAISE_BUDGET` choice posts to the existing `/api/v1/ai/apply` endpoint with the patch. That endpoint
already endorses only what the user changed (WS4), so the semantics are right: `budgetMax` becomes the new value
at full confidence, sticky for the session, and every later refinement inherits it.

The re-run after escalation searches the requested placement plus `escalationRadiusKm` (5 km), which is the
product owner's "2–5 km" expressed as a single ring with nearest-first ordering; `closeRadiusKm` (2 km) is the
labelling threshold within it rather than a separate query.

### 4.8 Unresolved names

When `Placement.source = NONE`, the search runs citywide — as today — but says so:

> **We couldn't place "Ulwe".** Showing results across Mumbai.

Tier framing does not apply (there is no anchor to measure from), and `distanceKm` is null on every row. After
§4.2's expansion this should be rare, and the geocoding workstream is expected to make it rarer still. The
frequency of this headline is the metric that tells us whether geocoding is worth its dependency.

A query naming no locality at all is a different case: citywide, no headline, no fallback framing, exactly as
today.

### 4.9 Degradation

- Null `properties.lat/lng` → locality centroid; the row still appears.
- An unknown anchor (no placement) → no distance columns, citywide, headline per §4.8.
- Extension creation failing on an exotic Postgres → the migration fails loudly at boot rather than silently
  falling back to sequential scans, consistent with Flyway owning the schema.

## 5. Testing

**Unit**

- `CommuteEstimatorTest` — `Nearby.km` matches the distance the minute figure derives from; the conversion table
  (2 km ≈ 16 min, 5 km ≈ 29 min) is asserted so the overhead constant cannot drift unnoticed.
- Ladder ordering: tiers appear in order, the ladder stops at `minResults`, and tiers 2–3 never contain a row
  violating a non-budget filter.
- Tier 1 and 2 contain no row above `budgetMax`; tier 3 contains no row above `budgetMax × 1.1`.
- **Intent immutability:** a search whose results include tier 3 rows leaves `budgetMax` unchanged; a subsequent
  refinement still carries the original figure. This is the regression the product owner explicitly asked for.
- `choices` counts match an independent query for the same criteria.
- `SeedLocalitiesTest` count assertions updated (38 → new count) and the distinct-name invariant kept.

**Integration** (`SearchPipelineIntegrationTest`, Testcontainers + seed data)

- The reported bug, end to end: "single sharing room in Kandivali under 15k" returns Kandivali rows in tier 1, or
  a headline plus nearby rows — never an unlabelled Kurla row.
- Escalation: applying a `RAISE_BUDGET` choice re-runs within 5 km and the new budget persists into the next
  refinement.
- An unresolved name produces the §4.8 headline and null distances.

**Unchanged and verified, not assumed:** WS3's `IntentGoldenTest` must still pass — intent extraction is not
touched by this work, and a change there would mean something leaked.

## 6. Implementation order

1. `V3__geo.sql` + extensions + index.
2. `CommuteEstimator` km on `Nearby`; ring config in km; `FlatmaiteProperties.Search` and README updated.
3. Gazetteer expansion, `LISTING_COUNT` / `USER_COUNT`, `SeedLocalitiesTest`; re-seed and confirm per-locality coverage.
4. `Placement` + resolution ladder step 2 (society / address).
5. Tier ladder replacing `RescueLadder.rungs()`; headroom moved out of the base filter.
6. `choices` computation and the `/apply` escalation path.
7. API DTO fields.
8. Frontend grouping, headline, terminus, distance chips.
9. Full `./mvnw verify` in the foreground, plus `npm run lint` and `tsc --noEmit`.

Steps 1–3 are independently useful: after step 3 the reported bug stops reproducing, because Kandivali resolves.
Steps 4–8 are what make the *next* unplaceable name behave well.

## 7. Files

**Backend, changed:** `search/CommuteEstimator`, `search/HybridRetriever`, `search/SearchPipeline`,
`search/RescueLadder`, `search/LocalityResolver`, `search/SearchDtos`, `search/AiSearchController`,
`common/config/FlatmaiteProperties`, `seed/SeedLocalities`, `seed/SeedRunner`.

**Backend, new:** `db/migration/V3__geo.sql`, `search/Placement`.

**Frontend, changed:** `app/(app)/search/search-screen.tsx`, `components/search/AiMatchCard.tsx`,
`lib/ai-client.ts`.

**Docs:** `README.md` environment table.

**Out of scope, separate workstream:** `GeocodingProvider` and implementations, `geocode_cache`, Mumbai
bounding-box clipping, provider terms review.
