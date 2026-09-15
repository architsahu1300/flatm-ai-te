# WS4 — Confidence-Gated Filtering: design

Date: 2026-09-15 · Status: draft for review · Follows WS2 (`2026-09-15-query-understanding-design.md`) and WS3 (`2026-09-15-eval-harness-design.md`)

## 1. Problem

Every slot the reader fills becomes a hard SQL `AND`, whether the user stated it or the reader guessed it.

- `"2bhk in powai"` → the parser infers `roomType = ENTIRE` (bare nBHK → entire flat) → `AND l.room_type = 'ENTIRE'`
  deletes every private room inside a 2BHK in Powai. The user never said "entire flat".
- `"room near bkc"` → `commuteTo.maxMinutes` defaults to 30 → listings 35 minutes away are deleted, though no
  radius was ever stated.
- A fuzzy locality match (`"powaii"` → Powai at 0.58) is enforced exactly as hard as an exact one.
- An LLM-inferred `furnished` or lifestyle value, read off tone rather than words, filters as hard as a quote.

A guess and a statement are indistinguishable downstream, and the user is never told which is which. WS3's eval
made this measurable: `roomType` is the slot where the key and the model most often disagree, and it is enforced
hardest.

The same rigidity shows up a second way. WS2 made a named locality always admit its ~25-minute neighbourhood, so
`"private room in Goregaon"` already surfaces Ram Mandir and Malad listings ranked behind the Goregaon ones. But
when even that comes back thin — two results, or none — nothing fills the page automatically: the user gets the
short list plus *buttons* they must click ("Search all of Mumbai — shows 12 more"). A thin page is the moment the
product should be most helpful, and it is the moment it does the least.

## 2. Goals / non-goals

Goals
- A per-slot confidence on `SearchIntent`, `min(extractive grounding computed in code, the model's self-rating
  when it offers one)`; chip edits and relaxer clicks (`/apply`) set 1.0.
- Grounding computed from `(intent, originalQuery)` alone, so the same rule grades the keyword parser and the
  LLM — neither path needs to report spans.
- Below the hard threshold a slot leaves the SQL `WHERE` and becomes a ranking preference: nothing is deleted,
  matching listings rank above non-matching ones.
- The UI distinguishes a filter from a preference, and the response says so in words.
- Relaxers offered lowest-confidence first.
- A thin result set is topped up automatically — a wider radius first, then the least-confident hard filters —
  with every added result marked as a near miss and the reason stated, instead of leaving the user to click.

Non-goals
- No change to what the reader extracts (WS2 behaviour stays frozen except where this spec names it).
- No schema change, no new dependency, no new provider call.
- Not extending WS3's golden format to grade confidence — `IntentGroundingTest` covers grounding as unit tests;
  teaching the eval to assert soft/hard is a follow-up.
- Flatmate scoring keeps its current shape; gating applies to the listing (`PROPERTIES`) path, and to the
  flatmate path only where the same intent slots already feed `ListingFilters`.

## 3. Decisions taken with the product owner

| Topic | Decision |
|---|---|
| Formula | `confidence = min(grounding, llmSelfRating)`; grounding alone when the model offers none (decided 2026-09-15, WS2 planning) |
| Behaviour | **A** — inferred slots become preferences; stated slots stay hard filters |
| Chip edits | `/apply` sets every present slot to 1.0 — the user endorsed the chip set |
| UI | Soft constraints are visibly distinct (`≈` chip), never silently downgraded |
| Thin results | **A + C**: gating removes the guesses up front; an automatic rescue ladder tops up a thin page with nearby and near-miss results (decided 2026-09-15, mid-WS4) |

## 4. Design

### 4.1 Where confidence lives

`SearchIntent` gains one additive field:

```java
    Map<String, Double> confidence,   // slot name → 0.0–1.0; absent key = 1.0 (stated or unknown-but-trusted)
```

Additive and nullable on a record already annotated `@JsonInclude(NON_NULL)` + `@JsonIgnoreProperties(ignoreUnknown
= true)`, so intent JSON stored in existing sessions deserialises with `null` and older readers ignore the key. No
migration. A `null` map and an absent key both mean **1.0** — the safe reading, because everything shipped before
WS4 was enforced hard.

Slot keys (17, the intent's own field names; `commuteTo` and its radius are graded separately because a defaulted
radius must not soften a stated anchor):

```
locations, excludeLocations, budgetMin, budgetMax, maxDeposit, roomType, listingTypes, furnished,
bhk, moveInDate, genderPreference, couplesOk, amenities, lifestyle, commuteTo, commuteTo.maxMinutes, verifiedOnly
```

`searchTarget` is not gated — it selects which index is queried, not which rows survive.

Helper on `SearchIntent`: `double confidenceOf(String slot)` → the map value, or `1.0` when the map or key is
absent.

### 4.2 Grounding (`search/IntentGrounding`, new, pure)

`static Map<String, Double> score(SearchIntent intent, String query, LocalityResolver resolver)` — one pass over
the tokenised query (WS2's `Tokens`), asking per non-null slot: **can I point at the words that produced this?**

Three levels, and nothing in between:

| Level | Meaning | Value |
|---|---|---|
| Stated | an explicit span in the query produced this value | `1.0` |
| Weak | a span supports it, but the mapping is a product convention rather than the user's words | `0.75` |
| Inferred | no span supports it — shape inference, a default, or the model's own reading | `0.5` |

Per slot:

- `locations`, `excludeLocations`, `commuteTo` — the maximum `LocalityResolver.Match.confidence()` over the
  matches that produced them (exact `1.0`, fuzzy `0.55–0.75`); a name present in the intent but absent from the
  query scan (the model supplied it) scores `0.5`.
- `commuteTo.maxMinutes` — `1.0` when the query states minutes (`COMMUTE_MIN` / `COMMUTE_MIN_FROM` match),
  else `0.5` (the `DEFAULT_COMMUTE_MINUTES` default).
- `budgetMin`, `budgetMax`, `maxDeposit` — `1.0` when an amount span in the query renders to the value (k /
  lakh / rupee / plain / spelled, per WS2's `NumberWords`), else `0.5`.
- `roomType` — `1.0` when `RentalVocabulary.explicitRoomType(query)` is non-null and equals the value; `0.75`
  when the query contains a bare occupancy noun (`room`) consistent with the value; else `0.5` (shape inference:
  bare `nBHK`, `apartment`).
- `furnished` — `1.0` on an explicit furnishing phrase, else `0.5`.
- `bhk` — `1.0` when the query contains `\dbhk|\d\s*bhk` (or `1rk`/`studio` for the ENTIRE shapes), else `0.5`.
- `listingTypes`, `amenities` — `1.0` when every value has a supporting span, else `0.5`.
- `genderPreference`, `couplesOk`, `verifiedOnly` — `1.0` on an explicit cue, else `0.5`.
- `moveInDate` — `1.0` when a date or a month word appears, else `0.5`.
- `lifestyle` — one key for the whole block: `1.0` when every non-null facet has a cue in the query, `0.75` when
  some do, `0.5` when none do. (Per-facet grading is a follow-up.)

`IntentGrounding` is pure: no Spring, no database — it takes the resolver it needs as an argument, the way
`IntentLocalities` does.

### 4.3 The model's self-rating

`OpenAiLlms.INTENT_RULES` gains one instruction and the schema one optional field: the model may return
`"confidence": {"<slot>": 0.0–1.0}` for the slots it filled, rating **how directly the user's words state it**,
not how sure it is of its own JSON. Unknown keys are dropped; values are clamped to `[0,1]`; a malformed or
absent object leaves the LLM side out of the `min`. `SearchIntent.confidence` after extraction is

```
confidence[slot] = min(grounding[slot], selfRating[slot] == null ? 1.0 : selfRating[slot])
```

computed once, in `SearchPipeline.extractIntent`, immediately before `IntentLocalities.resolve` — so every path
(LLM, mock/keyword, heuristic refinement, cache) is graded by exactly the same code. The mock offers no
self-rating, so the offline eval grades pure grounding.

**Merging on refinement:** a slot carried over from the prior intent keeps the prior's confidence; a slot the
follow-up re-states is re-graded against the follow-up's query. `MockLlms.MockIntentLlm` and `OpenAiLlms`'s
refine path merge the map with the same "parsed non-null wins" rule they already use for values.

### 4.4 Gating (`search/ConfidenceGate`, new, pure; applied in `HybridRetriever.toFilters`)

The threshold and the never-soft set live in one pure class so the retriever and the scorer share them without
the scorer depending on a Spring bean:

```java
  public static final double HARD_THRESHOLD = 0.75;   // aligns with LocalityResolver.CONFIDENT
  public static final Set<String> ALWAYS_HARD = Set.of("excludeLocations", "verifiedOnly");
```

A slot is **hard** when `intent.confidenceOf(slot) >= HARD_THRESHOLD` or it is in `ALWAYS_HARD`; otherwise it is
**soft** and simply not mapped into `ListingFilters`. `excludeLocations` and `verifiedOnly` are never softened:
a negation the user typed must be honoured, and quietly surfacing unverified listings to someone who asked for
verified ones is the opposite of the product's promise. Both are also always explicitly stated in practice — the
allow-list is a guarantee, not a workaround.

`ConfidenceGate.softSlots(SearchIntent)` → the ordered list of slot names that are non-null and soft; used by the
scorer, the pipeline note and the frontend.

Saved-search alerts (`SavedSearchAlertRunner`, strict mode) **strip the confidence map entirely** before
filtering — every slot the user saved is enforced, whatever its grade. *Amended after the WS4 final review.*
The original rule ("alerts apply the same gating") assumed the gating bargain holds there, but it does not:
everywhere else a soft slot leaves the `WHERE` and becomes a *ranking* preference, and the alert query does not
rank — it takes the five newest rows that match and sends them. A soft slot in an alert is therefore deleted,
not demoted, and a saved Powai search whose `locations` graded 0.58 would alert on every new listing in Mumbai.
Saving a search is the same gesture `/apply` represents, so it is treated as the same endorsement.

### 4.5 Scoring a soft slot (`MatchScorer`)

A soft slot that an existing component already represents needs no new term — dropping it from the `WHERE` is
enough, because the component already ranks it:

| Soft slot | Already scored by |
|---|---|
| `budgetMin`, `budgetMax`, `maxDeposit` | `budgetFit` (0.20) |
| `locations`, `commuteTo`, `commuteTo.maxMinutes` | `location` (0.20) |
| `lifestyle` | `lifestyle` (0.20) |

Every other soft slot — `roomType`, `furnished`, `bhk`, `listingTypes`, `amenities`, `genderPreference`,
`couplesOk`, `moveInDate` — has no scoring representation today, so those become one new component:

```java
new Component("preferences", 0.15, satisfied / (double) total, detail)
```

present **only** when at least one such slot is soft. `MatchScorer.finish` already renormalises over the
components that apply, so scores stay comparable. `detail` names what matched and what did not, in the grounded
style the explainer requires: `"Matches your preferred %s"` when all are satisfied, `"%s — a preference, not a
requirement"` naming the unsatisfied ones otherwise. A listing that fails every soft preference still appears,
scored down, with the miss stated.

`ListingCandidate` gains nothing: the scorer reads the soft set from the intent it already receives.

### 4.6 Telling the user

- **Chips** (`frontend/src/lib/ai-client.ts`): the mirror gains `confidence?: Record<string, number> | null`;
  a chip whose slot is soft renders its value prefixed `≈` and carries `soft: true`, so the UI can dim it and
  the title reads "preference, not a filter". Removing a soft chip works exactly as before.
- **Response note** (`SearchPipeline`): when any slot is soft, append
  `"Some of these are preferences, not filters: %s."` with the soft slots' display labels, comma-joined —
  alongside the existing nearby-areas note.
- **Relaxers**: a soft slot is already not filtering, so it gets no relaxer; the remaining relaxers are ordered
  by ascending confidence of the slot they relax, so the shakiest constraint is offered first.
- **`/apply`**: the controller rebuilds the confidence map server-side (the client's is never trusted) from the
  session's stored intent: a gated slot whose **value the user changed** becomes `1.0` — they are the author of
  what they sent, so from then on it is a filter — and a slot they **left alone keeps the grade it had**.
  *Amended after the WS4 final review.* The original rule ("`1.0` for every non-null gated slot") is wrong
  because `/apply` is also the endpoint that fires when the user removes **one unrelated chip**: endorsing
  everything still present would silently turn a guessed `roomType = ENTIRE` into a hard `AND` because someone
  dropped their deposit chip — the exact bug this workstream exists to remove, and the opposite of what the soft
  chip's own tooltip promises ("A preference, not a filter — say it outright to require it"). The
  changed-vs-untouched discriminator is `ConfidenceGate.sameValue`, the same one `carryConfidence` uses, so the
  two paths agree on what "the user did not touch this" means. The grades are **written out explicitly**, never
  cleared: `carryConfidence` early-returns on an absent map, so an endorsement left implicit would evaporate on
  the next conversational turn.

### 4.7 Thin-result rescue (the automatic ladder)

Gating (§4.4) decides what is enforced. This decides what happens when enforcing it leaves too little on the page.

```java
  static final int MIN_RESULTS = 6;               // one screen of cards
  static final int RESCUE_RADIUS_MINUTES = 45;    // the second ring, beyond the always-on 25
```

After the hard-filtered search returns, if the listing count is `>= MIN_RESULTS` nothing changes. Otherwise the
pipeline walks a **deterministic ladder**, re-running retrieval at each rung and appending only listings not
already present, stopping the moment the count reaches `MIN_RESULTS` or the ladder is exhausted:

1. **Widen the ring** — only when the intent names a locality or a commute anchor: re-admit at
   `RESCUE_RADIUS_MINUTES` instead of `nearbyRadiusMinutes`, still ranked by distance.
2. **Drop the least-confident hard slot** — one rung per slot, ascending by `confidenceOf`, ties broken by a
   fixed slot order so the same intent always produces the same rescue. `ALWAYS_HARD` slots
   (`excludeLocations`, `verifiedOnly`) are never in the ladder: a stated negation and a safety filter are
   promises, and a rescue that breaks them is worse than a short page.
3. **Stop.** The existing relaxer suggestions remain for anything further ("Search all of Mumbai"), now ordered
   lowest-confidence first (§4.6).

Soft slots never appear in the ladder — they are already not filtering.

Every appended result carries `nearMiss = true` and a grounded `nearMissReason` (`"~38 min from Goregaon"`,
`"Semi-furnished — you asked for fully furnished"`), and sorts **below every exact match regardless of score**, so
the page never buries a true match under a near miss. The response note names what happened:
`"Only %d exact %s — added %d nearby option%s (%s)."` with the rung(s) used.

Saved-search alerts never run the ladder: an alert must fire on a real match, not a near miss.

### 4.8 Degradation

| Situation | Behaviour |
|---|---|
| No `confidence` map (old session, mock without grounding, WS3 report replay) | every slot reads 1.0 → today's behaviour exactly |
| Model returns a malformed/unknown-key `confidence` object | keys dropped, values clamped; grounding stands alone |
| Every slot soft (a vague query) | the query still runs — retrieval falls back to relevance + `preferences`; the note explains that nothing was enforced |
| A soft slot that `ALWAYS_HARD` covers | stays hard; `softSlots` never contains it |
| Grounding throws | logged at WARN, empty map returned → everything hard (fail closed, never silently wider) |
| Rescue ladder exhausted and still thin | the short list stands, the note says so, relaxers remain |
| Rescue would return nothing new at a rung | that rung is skipped silently; the ladder continues |
| No locality or commute anchor | rung 1 is skipped; the ladder starts at the least-confident hard slot |

## 5. Testing

| Test | Kind | Asserts |
|---|---|---|
| `IntentGroundingTest` (new) | pure | each level per slot: "under 25k" → `budgetMax` 1.0; "2bhk in powai" → `roomType` 0.5, `bhk` 1.0, `locations` 1.0; "private room in goregaon" → `roomType` 1.0; "room near bkc" → `roomType` 0.75, `commuteTo.maxMinutes` 0.5; "flats in powaii" → `locations` ≈ 0.58; model-supplied place absent from the query → 0.5 |
| `SearchIntentConfidenceTest` (new) | pure | `confidenceOf` defaults to 1.0 for null map / absent key; merge keeps the prior's value for a carried slot, re-grades a re-stated one |
| `HybridRetrieverGatingTest` (new) | pure | a 0.5 `roomType` is absent from `ListingFilters` while a 1.0 one is present; `excludeLocations`/`verifiedOnly` stay hard at 0.5; `softSlots` order; strict (alert) mode gates identically |
| `MatchScorerTest` (extended) | pure | the `preferences` component appears only with an ungraded soft slot, weight 0.15, score = satisfied/total, detail text; absent when every soft slot is already represented |
| `OpenAiLlmsPromptTest` (extended) | pure | the intent prompt asks for `confidence`; a malformed object is dropped; values clamp |
| `MockIntentLlmTest` (extended) | pure | refinement merge carries the prior's confidence for untouched slots |
| `SearchPipelineIntegrationTest` (extended) | Docker | `"2bhk in powai"` returns a listing whose `room_type` is not ENTIRE, the note names the preference, and the chip payload marks `roomType` soft |
| `ThinResultRescueTest` (new) | pure | ladder order (radius first, then ascending confidence, ties by fixed order); `ALWAYS_HARD` never dropped; a rung returning nothing is skipped; stops at `MIN_RESULTS`; near misses sort below every exact match |
| `SearchPipelineIntegrationTest` (extended) | Docker | a deliberately over-tight query returns `MIN_RESULTS` with the tail marked `nearMiss` and reasons stated; an alert-mode search on the same intent returns only exact matches |
| `IntentGoldenTest` (unchanged) | pure | WS3's gate must stay green — grounding changes no extracted value |

## 6. Implementation order

1. `SearchIntent.confidence` + `confidenceOf` + merge helper (additive; nothing reads it yet).
2. `IntentGrounding` + tests (pure, the heart of the change).
3. `SearchPipeline.extractIntent` computes and attaches confidence on every path.
4. `HybridRetriever` gating (`HARD_THRESHOLD`, `ALWAYS_HARD`, `softSlots`, `toFilters`), alerts included.
5. `MatchScorer.preferences` component.
6. Prompt self-rating + merge on refinement (`OpenAiLlms`, `MockLlms`).
7. Note, relaxer ordering, `/apply` endorsement (`SearchPipeline`, `AiSearchController`).
8. Thin-result rescue ladder (`SearchPipeline`), `nearMiss` on the result DTO, alerts excluded.
9. Frontend mirror + `≈` soft chips + near-miss badge; README; full verification.

## 7. Files

Create: `backend/src/main/java/com/flatmaite/search/{IntentGrounding,ConfidenceGate,RescueLadder}.java`,
`backend/src/test/java/com/flatmaite/search/{IntentGroundingTest,SearchIntentConfidenceTest,SearchPipelineConfidenceTest,HybridRetrieverGatingTest,ThinResultRescueTest}.java`.
Modify: `SearchIntent.java`, `SearchPipeline.java`, `HybridRetriever.java`, `MatchScorer.java`, `OpenAiLlms.java`,
`MockLlms.java`, `AiSearchController.java`, `SavedSearchAlertRunner.java` (call-site only), `SearchDtos.java`
(`AiResult.nearMiss`/`nearMissReason`), `common/config/FlatmaiteProperties.java` (`MIN_RESULTS`,
`RESCUE_RADIUS_MINUTES` as tunables), `resources/application.yml`,
`frontend/src/lib/ai-client.ts`, `README.md`, and the four existing tests named in §5.
No schema change; no new dependency.
