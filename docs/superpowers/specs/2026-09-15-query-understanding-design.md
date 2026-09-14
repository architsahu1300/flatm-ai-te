# WS2 — Query understanding: localities, distance, parser, arbiter, prompts

**Status:** approved design, pre-implementation
**Date:** 2026-09-15
**Scope:** backend `search/`, `ai/`, `seed/`, `listing/` (filters + seed data), `common/config`; a
Flyway-free change (no schema migration); frontend limited to the `SearchIntent` TypeScript mirror
and two new chips. Builds on WS1 (`docs/superpowers/specs/2026-09-14-retrieval-fusion-design.md`).

## 1. Problem

Wrong-location and wrong-intent results come from the query-understanding layer, not retrieval:

1. `LocalityResolver.scan/resolve` match by raw substring in iteration order. "bandra kurla
   complex" resolves to Bandra **and** BKC **and** Kurla; `resolve("Bandra Kurla")` returns Bandra.
   There are no word boundaries and no positions, so negation ("anywhere but Andheri" → filters
   *to* Andheri) and the home-vs-commute split ("room in Andheri, I work at BKC") are impossible.
2. Only 10 localities exist; Andheri East/West are one locality, "parel" is an alias of Lower
   Parel. Any other Mumbai name silently loses its location filter.
3. A named home locality is a strict `IN (...)` filter. Three Goregaon matches end the page while
   Malad and Ram Mandir sit 12 minutes away — offered only as a button after the page is empty.
4. `KeywordIntentParser` treats "more than 30000" as a ceiling, never sets `budgetMin`, sets
   `verifiedOnly` on "not verified", and cannot read "twenty five thousand" although the glossary
   promises the LLM it can.
5. Two arbiters decide new-vs-refine — `NewQueryDetector` (2-anchor rule) and the `REFINE_SYSTEM`
   prompt ("REPLACE if complete") — and disagree on 1-anchor messages; nothing resets
   `originalQuery` when the model replaces.
6. The intent prompt has no examples and no locality vocabulary; the prior intent (containing
   raw user text) is injected into the **system** message; temperature is 0.2 for an extraction
   task.

## 2. Goals / non-goals

**Goals**
- A resolver that is exact when it can be, fuzzy when it must be, position-aware always.
- Wider, correct locality data; `excludeLocations` and a clean commute anchor.
- Distance-aware admission: nearby areas appear automatically, ranked below exact matches.
- Parser parity with the glossary on amounts; correct floors/ceilings; negation-aware `verified`.
- One arbiter for new-vs-refine, with the model's opinion as a tie-breaker only.
- Prompt hygiene: vocabulary, few-shots, merge-only refine, prior intent in the user role, temp 0.

**Non-goals (deferred)**
- Strict JSON-schema structured outputs (WS3, once live runs exist).
- Per-slot confidence and hard-vs-soft filtering (WS4).
- Widening `search_tsv` with locality/society names; embedding-based locality linking.
- Frontend work beyond the type mirror and two chips.

## 3. Decisions taken with the product owner

| Question | Decision |
|---|---|
| LLM key | Gemini free tier available → prompt changes are verified live in WS3's eval run; WS2 verifies them structurally |
| Locality coverage | Split Andheri E/W and Parel/Lower Parel; add ~27 more with approximate centroids; seed grows to 80 listings |
| Resolver | Layered: exact gazetteer → in-memory trigram fuzzy → LLM canonicalisation via injected vocabulary; unresolved names surfaced and logged |
| New-vs-refine | Controller's detector is the referee; the LLM's `mode` is consulted only in the ambiguous zone |
| Distance | Always widen a named locality to its ~25-minute neighbourhood; rank by distance; note it in the response |

## 4. Design

### 4.1 `LocalityResolver` (rewrite, same class name)

**Index.** At `@PostConstruct`, build `Map<String, List<UUID>> byPhrase` from each locality's
lower-cased name and aliases (a phrase may map to several localities — "andheri" → Andheri East
and Andheri West) and `Map<UUID, String> nameById`. Phrases are tokenised the same way queries
are. Conflicting single-locality aliases are logged at WARN.

**Tokenisation.** `Tokens.of(text)`: lower-case, split on `[^\p{L}\p{N}]+`, keep each token's
character span. Shared by resolver, parser and detector.

**Layer 1 — exact.** For windows of 3, then 2, then 1 tokens, look up the joined phrase in
`byPhrase`. A window that overlaps an already-accepted longer match is skipped (longest wins).
Confidence 1.0.

**Layer 2 — fuzzy.** For each remaining single token of length ≥ 5, compute trigram Jaccard
similarity against every single-word phrase; accept the best if ≥ 0.55 (confidence = similarity,
so 0.55–1.0; the parser treats < 0.75 as "fuzzy"). Two-word phrases are compared against 2-token
windows the same way. Typos in short names ("bkc") are not corrected.

**Layer 3 — semantic** is the LLM (see 4.6): it receives the vocabulary and emits canonical
names, which hit layer 1. Not code in this class.

**API**

```java
public record Match(List<UUID> localityIds, String canonicalName, int start, int end,
                    String matchedText, double confidence) {}
public List<Match> scan(String text)            // non-overlapping, in text order
public Optional<Match> resolve(String name)     // whole-string match, layers 1–2
public String nameOf(UUID id)                   // unchanged contract
public List<String> vocabulary()                // "Name (alias, alias)" lines for the prompt
```

`resolve` never uses substring containment. Callers that previously received one `UUID` from
`resolve` now receive a `Match` and use `localityIds()` (usually one; two for an ambiguous alias).

### 4.2 `LocationMentions` (new, pure)

Turns `scan` output plus the token stream into roles:

- **exclude** if the token immediately before the match is a cue in `{not, no, except, excluding,
  avoid, nahi}`, or the token immediately before is a preposition and the token two back is such a
  cue, or the two tokens before are `other than` / `anywhere but` / `apart from`, or `nahi`/`mat`
  immediately follows the match.
- **commute** if any of the 4 tokens before the match is in `{near, nearby, close, around, next,
  within, work, working, office, commute, commuting}` (covers "near BKC", "close to BKC", "work at
  BKC", "office in BKC", "within 20 min of BKC").
- **home** otherwise.

Result: `Mentions(List<Match> home, List<Match> exclude, Optional<Match> commute,
List<String> unresolvedCandidates)`. A name that is both excluded and home (unlikely) is excluded.

### 4.3 Locality data (`SeedRunner`)

Splits: **Andheri East** (19.1136, 72.8697; alias "andheri east") / **Andheri West** (19.1364,
72.8296; alias "andheri west") — both also carry alias "andheri"; **Parel** (19.0090, 72.8400;
alias "parel") separate from **Lower Parel** (aliases "lower parel", "lp").

Additions (centroids approximate; rent bands are seed pricing only):

| Locality | lat, lng | aliases | band |
|---|---|---|---|
| Marol | 19.1197, 72.8823 | marol naka, mahakali | 20000 |
| Chakala | 19.1100, 72.8630 | jb nagar, j b nagar | 21000 |
| Sakinaka | 19.1050, 72.8880 | saki naka | 16000 |
| Jogeshwari | 19.1360, 72.8490 | jogeshwari east, jogeshwari west | 17000 |
| Ram Mandir | 19.1480, 72.8450 | ram mandir road | 16000 |
| Vile Parle | 19.0996, 72.8440 | vile parle east, vile parle west, parle | 26000 |
| Santacruz | 19.0817, 72.8414 | santa cruz, santacruz east, santacruz west | 28000 |
| Khar | 19.0700, 72.8340 | khar west, khar east | 32000 |
| Juhu | 19.1075, 72.8263 | juhu beach | 34000 |
| Mahim | 19.0410, 72.8408 | — | 26000 |
| Dadar | 19.0178, 72.8478 | dadar east, dadar west, shivaji park | 27000 |
| Matunga | 19.0270, 72.8553 | matunga east, matunga west | 26000 |
| Sion | 19.0390, 72.8619 | sion east | 20000 |
| Wadala | 19.0176, 72.8562 | wadala east | 22000 |
| Chembur | 19.0522, 72.9005 | chembur east | 19000 |
| Vikhroli | 19.1080, 72.9280 | vikhroli east, vikhroli west | 18000 |
| Kanjurmarg | 19.1283, 72.9350 | kanjur marg | 17000 |
| Bhandup | 19.1440, 72.9370 | — | 15000 |
| Mulund | 19.1726, 72.9564 | mulund west | 17000 |
| Thane | 19.2183, 72.9781 | thane west, ghodbunder | 15000 |
| Kandivali | 19.2045, 72.8519 | kandivali east, kandivali west | 15000 |
| Borivali | 19.2307, 72.8567 | borivali west, borivali east | 16000 |
| Vashi | 19.0771, 72.9987 | navi mumbai | 17000 |
| Airoli | 19.1590, 72.9986 | — | 15000 |
| Kharghar | 19.0330, 73.0650 | — | 13000 |
| Colaba | 18.9067, 72.8147 | cuffe parade | 40000 |

Existing localities gain aliases: Powai ← "hiranandani", "hiranandani gardens", "iit bombay";
Goregaon ← "film city"; Malad ← "malad east", "mindspace"; Kurla ← "kurla east"; Ghatkopar ←
"ghatkopar west"; BKC ← "bandra kurla", "bkc road". `LISTING_COUNT` 50 → **80** (types spread
proportionally: 29 private, 16 shared, 16 entire, 13 looking-for-flatmate, 6 replacement) so
localities are not empty; `FLATMATE_COUNT` stays 35. The seed remains `Random(42)`-deterministic.
Seed facts relied on by WS1 tests ("wardrobe"/"essentials"/"flatmate" text, active listings <
`VECTOR_LIMIT`) are preserved: 80 × ~80 % active ≈ 64 < 100. After inserting localities the seed
runner calls `LocalityResolver.reload()` and `CommuteEstimator.reload()` — both caches fill in
`@PostConstruct`, before any runner, so a freshly seeded database would otherwise be unsearchable
until restart.

### 4.4 `SearchIntent` additions (additive, JSON-compatible)

```java
List<LocationRef> excludeLocations,   // "anywhere but Andheri"
List<String> unresolvedLocations      // names no layer could place; also kept in freeText
```
plus `public static final int DEFAULT_COMMUTE_MINUTES = 30` replacing the four `45` literals
(`KeywordIntentParser`, `HybridRetriever.admittedLocalityIds`, `MatchScorer`,
`RefinementHeuristics`). Frontend `SearchIntent` mirror gains both fields; `chipsFromIntent`
adds a removable "🚫 Not in {name}" chip per exclusion and a removable "📍? Couldn't place
\"{name}\"" chip per unresolved name.

### 4.5 Distance-aware admission

- New `FlatmaiteProperties.Search { int nearbyRadiusMinutes = 25; }` bound from
  `flatmaite.search.nearby-radius-minutes: ${SEARCH_NEARBY_RADIUS_MINUTES:25}`.
- `HybridRetriever.admittedLocalityIds(intent)`: for each requested locality id, add it and every
  locality within `nearbyRadiusMinutes` (`CommuteEstimator.nearestLocalities`); for `commuteTo`,
  add every locality within its `maxMinutes` (default `DEFAULT_COMMUTE_MINUTES`); then remove every
  id in `excludeLocations`. Returned list order: requested first, then by ascending minutes.
- `ListingFilters.excludeLocalityIds` → `AND p.locality_id NOT IN (:excludeLocalityIds)`;
  flatmates `AND NOT (fp.locality_ids && CAST(:excludeIds AS uuid[]))`.
- `SearchPipeline.searchHomes`: the commute anchor for scoring becomes the **nearest requested
  locality** (min minutes over requested ids; `commuteTo` anchor wins when present).
  `commuteLabel` reads "~12 min from Goregaon (estimate)" for home-locality anchors and keeps
  "~12 min to BKC (estimate)" for commute anchors. Response `note` is set to
  "Also showing nearby areas within ~25 min" when any returned home is outside the requested
  localities **and the intent carries no commute anchor** (with a commute anchor the per-result
  "~N min to X" label already explains an out-of-area home, appended to any existing note).
- `MatchScorer.scoreListing` location component: in a requested locality → 1.0; otherwise
  `max(0.3, 1 − minutes / (2 × radius))` where radius = `commuteTo.maxMinutes` for commute intents
  or `nearbyRadiusMinutes` for home intents (passed in via `ListingCandidate.radiusMinutes`).
  Detail: "In Goregaon — one of your preferred areas" / "~12 min from Goregaon (estimate)".
- Flatmates: admitted set widened identically; `locationOverlap` (Jaccard) computed against the
  **requested** ids only.
- `SearchPipeline.computeRelaxers`: `nearbyAreaRelaxers` removed; remaining relaxers unchanged.
- Saved-search alerts (`SavedSearchAlertRunner`) call `toFilters(intent, false)`: exact requested
  localities plus any explicit commute radius, never the nearby widening — an alert has no note in
  which to explain a widened area.

### 4.6 Parser (`KeywordIntentParser`) and `NumberWords`

- `NumberWords.parse(String phrase)` → `OptionalInt`: units/teens/tens, "hundred", "thousand",
  "lakh|lac", decimals in "one and a half lakh"/"1.5 lakh"; returns empty for anything else.
- Amount extraction produces `(value, span)` pairs from: `\d+(\.\d+)?\s*k`, `\d+(\.\d+)?\s*(lakh|lac|l)\b`,
  `₹|rs|inr\s*\d{4,7}`, bare `\d{4,7}`, and spelled-out phrases. Each amount is classified by the
  3 tokens before it: floor cues `{above, over, more, least, minimum, min, from, starting}` →
  `budgetMin`; ceiling cues `{under, below, upto, up, max, maximum, within, less, than?}` — "than"
  is a ceiling only after "less"/"lesser", a floor after "more"/"greater". "between A and B",
  "A to B", "A-B" → min/max. "deposit" context unchanged. Without a cue, a single amount is a
  ceiling (existing behaviour).
- `verifiedOnly = true` only when "verified" occurs and is not preceded within 2 tokens by
  `{not, un, non}` and the token itself is not "unverified".
- Locations via `LocationMentions`: `locations` = home matches (all ids of each match; ambiguous
  aliases yield two `LocationRef`s), `excludeLocations`, `commuteTo` from the commute match with
  `COMMUTE_MIN` minutes else `DEFAULT_COMMUTE_MINUTES`; `unresolvedLocations` is only produced by
  the LLM path (the parser has no candidates it cannot resolve).

### 4.7 Arbiter

`NewQueryDetector.decide(String query)` → `Verdict { NEW, REFINE, AMBIGUOUS }`:

1. fresh cue present (`forget that|forget it|start over|new search|scrap that|from scratch`) → NEW
2. anchors = locality (any `scan` match with confidence ≥ 0.75) + budget + roomType + BHK;
   anchors ≥ 3 → NEW
3. refinement cue present (`make it|instead|also|actually|same but|but in|rather|change (it|the)|
   cheaper|closer|nearer`) → REFINE
4. anchors ≥ 2 and housing noun → NEW
5. anchors == 0 → REFINE
6. otherwise AMBIGUOUS

`isSelfContained(query)` remains as `decide(query) == NEW` (existing tests unchanged).

`IntentLlm.extract` returns `Extraction(SearchIntent intent, Mode mode)` where `Mode { NEW,
REFINE, UNSURE, NONE }`; the mock and the heuristics return `NONE`. The OpenAI/Gemini refine call
parses `RefineResult(SearchIntent intent, String mode)`.

`AiSearchController.search`:
```
verdict = detector.decide(query)
if prior != null and verdict == NEW → prior = null, note = fresh-search note
extraction = pipeline.extractIntent(query, prior, …)
if prior != null and verdict == AMBIGUOUS and extraction.mode() == NEW
    → prior = null, extraction = pipeline.extractIntent(query, null, …), note = fresh-search note
```
The fresh path already resets `originalQuery` and `freeText`.

### 4.8 Prompts (`OpenAiLlms`)

- `INTENT_SYSTEM` = rules (unchanged) + `RentalVocabulary.GLOSSARY` + **vocabulary block**:
  "Known localities — use these canonical names in locations, excludeLocations and commuteTo;
  map landmarks and aliases onto them; a place not in this list goes into locations exactly as the
  user wrote it:" followed by one line per locality `Name (alias, alias)` from
  `LocalityResolver.vocabulary()` + **examples block** of 9 `Query → JSON` pairs: single sharing
  → PRIVATE; Hinglish complete request; "anywhere but Andheri" → excludeLocations; "room in
  Andheri, I work at BKC" → locations vs commuteTo; "twenty five thousand" → 25000; "more than
  30000" → budgetMin; "between 20k and 30k"; "female flatmate, no pets, quiet"; landmark
  "near Hiranandani Gardens" → Powai. The block is assembled once at bean construction
  (`OpenAiIntentLlm` gains a `LocalityResolver` dependency).
- `REFINE_SYSTEM`: replace clause removed. New text: "Apply the follow-up to the current intent
  and return the FULL merged intent. Never drop fields the user did not change. Also report mode:
  NEW if the follow-up reads as a complete request on its own, REFINE if it adjusts the current
  search, UNSURE otherwise — the caller decides what to do with it; you always merge."
  The prior intent JSON moves to the **user** message: `"Current intent:\n<json>\n\nFollow-up:\n<query>"`.
- `spring.ai.openai.chat.options.temperature` and `spring.ai.google.genai.chat.options.temperature`
  → `0`.
- `resolveLocalities` (SearchPipeline) runs `resolve(name)` on every `locations`,
  `excludeLocations` and `commuteTo.place`; a name with no match moves to `unresolvedLocations`
  (removed from `locations`), and is appended to `freeText` if absent. Unresolved names are
  logged at INFO with the query (`search.unresolved-locality`) for the WS3 eval report.

### 4.9 Degradation

| Situation | Behaviour |
|---|---|
| Alias ambiguity ("andheri") | both localities requested; both count as "preferred" for scoring |
| Fuzzy match < 0.75 | accepted, but detector does not count it as an anchor; WS4 will lower its confidence |
| LLM emits an unknown place | moved to `unresolvedLocations`, kept in `freeText`, chip shown |
| Model returns no `mode` / unparsable | `Mode.UNSURE` → detector's verdict stands (REFINE) |
| `excludeLocations` names a requested locality | exclusion wins: the locality is dropped from the request; if nothing requested remains, the search runs city-wide minus the exclusions |
| `excludeLocations` removes every admitted listing | empty result → existing relaxers; "Search all of Mumbai" drops `locations` and `commuteTo` but keeps `excludeLocations` |

## 5. Testing

| Test | Kind | Proves |
|---|---|---|
| `TokensTest` (new) | pure | spans, Unicode split, lower-casing |
| `LocalityResolverTest` (new, mocked repo) | pure | longest match ("bandra kurla complex" → BKC only), "andheri" → two ids, word boundary ("mansion" ≠ Sion), fuzzy ("powaii" → Powai ≥ 0.55, "pow" → none), `resolve` exact/fuzzy/none, `vocabulary()` format |
| `LocationMentionsTest` (new) | pure | exclude / commute / home cue windows on real sentences incl. "room in Andheri, I work at BKC" and "anywhere but Andheri" |
| `NumberWordsTest` (new) | pure | "twenty five thousand"=25000, "one lakh"=100000, "one and a half lakh"=150000, "thirty"=30, garbage → empty |
| `KeywordIntentParserTest` (new) | pure | floors vs ceilings incl. "more than 30000" → min, ranges, spelled-out, negated verified, exclude, live/work split, ambiguous alias → two refs, default commute 30 |
| `NewQueryDetectorTest` (extended) | pure | existing 13 cases unchanged; fresh cues → NEW; refinement cues with 2 anchors → REFINE; 1-anchor+no-noun → AMBIGUOUS |
| `OpenAiLlmsPromptTest` (new) | pure | system prompt contains every locality name, ≥ 9 examples, no "REPLACE"; refine user message contains the prior JSON; `RefineResult` parses with and without `mode` |
| `LocationWideningIntegrationTest` (new, Testcontainers) | seed | "private room in Goregaon" admits Ram Mandir/Malad listings, every Goregaon listing ranks above any non-Goregaon one with equal other components (assert by locality order of the top results), note present; "anywhere but Goregaon" returns no Goregaon; ≥ 35 localities seeded; "Ram Mandir" resolves |
| `SearchPipelineIntegrationTest` (extended) | seed | end-to-end exclude via `/search`; `unresolvedLocations` chip data for an unknown name via the mock path is empty (parser never produces it) |
| WS1 suites | — | all remain green (seed facts preserved) |

Prompt/LLM behaviour is verified live in WS3.

## 6. Implementation order

1. `Tokens`, `NumberWords` (pure) → 2. `LocalityResolver` rewrite + `LocationMentions` + seed data
(+ `LISTING_COUNT` 80) → 3. `SearchIntent` fields + filters + `DEFAULT_COMMUTE_MINUTES` + frontend
mirror/chips → 4. Distance-aware admission + scoring + note + relaxer removal → 5. Parser rewrite
of budget/verified/locations → 6. Arbiter (`decide`, `Extraction`, `RefineResult`, controller) →
7. Prompts + temperature + `resolveLocalities` unresolved handling → 8. Integration tests +
full verify.

## 7. Files

**New:** `search/Tokens.java`, `search/NumberWords.java`, `search/LocationMentions.java`,
`ai/RefineResult.java` (or nested), tests listed above.
**Modified:** `search/LocalityResolver.java`, `search/KeywordIntentParser.java`,
`search/NewQueryDetector.java`, `search/SearchIntent.java`, `search/SearchPipeline.java`,
`search/HybridRetriever.java`, `search/MatchScorer.java`, `search/RefinementHeuristics.java`,
`search/AiSearchController.java`, `ai/IntentLlm.java`, `ai/MockLlms.java`, `ai/OpenAiLlms.java`,
`ai/AiProviderConfig.java`, `listing/ListingFilters.java`, `listing/ListingQueryService.java`,
`seed/SeedRunner.java`, `common/config/FlatmaiteProperties.java`, `resources/application.yml`,
`frontend/src/lib/ai-client.ts`.
