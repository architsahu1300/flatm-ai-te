# WS3 — Intent Eval Harness: design

Date: 2026-09-15 · Status: draft for review · Follows WS2 (`docs/superpowers/specs/2026-09-15-query-understanding-design.md`)

## 1. Problem

WS1 and WS2 left 200 green tests, each pinning one behaviour. Nothing measures intent quality *as a whole*
across realistic queries, nothing compares the keyword parser with the real LLM, and three WS2 mechanisms have
never run against a live provider: `BeanOutputConverter<RefineResult>` schema generation, the model's `mode`
hint in the AMBIGUOUS zone, and the new `promptOverheadTokens()` estimate. The WS2 final review named the
eval slices it wants: budget classification (floors/ceilings/ranges/spelled amounts, amount after a commute
phrase, amount after a place), contradictory-location follow-ups, the AMBIGUOUS zone end to end, fuzzy-layer
precision, and real provider token usage versus the estimate.

## 2. Goals / non-goals

Goals
- A golden set of `query → expected SearchIntent` cases, including multi-turn refine/new cases with an expected
  arbiter verdict.
- Per-slot scoring with a must-pass subset and aggregate thresholds; a diff line for every failing slot.
- A fast, pure JUnit gate in `./mvnw verify` that scores the keyword parser (the mock LLM) and the arbiter.
- An `eval` Spring profile that runs the same golden set through the real provider wiring (Gemini via
  `FM_AI_PROVIDER=google-genai`), paced for the free tier, and writes a report. Live runs never gate the build.
- Reports as a console table plus JSON under `backend/target/eval/`; the first Gemini run's summary committed
  under `docs/eval/`.

Non-goals
- Scoring result sets or ranking (integration tests own that). No UI. No CI secrets — live runs are manual.
- Changing intent extraction behaviour. Findings from the first live run become WS4 input, not WS3 fixes,
  unless a live run exposes a plain bug (e.g. the converter rejecting `RefineResult`).

## 3. Decisions taken with the product owner

| Topic | Decision |
|---|---|
| Live key handling | Archit runs the live eval from his own terminal with the key exported; the assistant reads the output. The key never enters the repo or the assistant's context. |
| Offline gate | Fails the build (must-pass cases exact; aggregate thresholds in §4.2). |
| Scope | Intent + arbiter level only. |

## 4. Design

### 4.1 Golden set

File: `backend/src/main/resources/eval/intent-golden.json` (main resources so both entry points read it from the
classpath). Top level `{ "version": 1, "cases": [ ... ] }`. A case:

```json
{
  "id": "budget-after-commute-phrase",
  "tags": ["budget", "commute"],
  "query": "room within 20 min of bkc, 25k",
  "prior": null,
  "mustPass": true,
  "expectVerdict": null,
  "expect": {
    "searchTarget": "PROPERTIES",
    "commuteTo": { "place": "BKC", "maxMinutes": 20 },
    "budgetMax": 25000,
    "roomType": "PRIVATE"
  }
}
```

- `prior`: `null` for a first turn, or `{ "case": "<id of an earlier case>" }` — the prior intent is that case's
  **expected** intent (provider-independent), so refine quality is measured on its own.
- `expectVerdict`: `NEW | REFINE | AMBIGUOUS` for follow-ups where the arbiter's verdict is part of the
  expectation; `null` = not scored. `scoreIntent: false` (default `true`) grades only the verdict — used
  for AMBIGUOUS follow-ups, whose resulting intent legitimately differs by which way the tie broke.
- `expect` lists every scored slot the case cares about; **an omitted scored slot is expected to be null/empty**,
  so a hallucinated constraint fails the case.
- Scored slots (§4.2): `searchTarget`, `locations`, `excludeLocations`, `unresolvedLocations`, `commuteTo`
  (`place`, `maxMinutes`), `budgetMin`, `budgetMax`, `maxDeposit`, `roomType`, `listingTypes`, `bhk`
  (`min`, `max`), `furnished`, `genderPreference`, `couplesOk`, `verifiedOnly`, `amenities`,
  `lifestyle.smoking`, `lifestyle.pets`, `lifestyle.diet`, `lifestyle.quiet`. Not scored: `freeText`,
  `originalQuery`, `moveInDate`, `leaseMonths`, the remaining lifestyle fields.
- Localities (`locations`, `excludeLocations`, `commuteTo.place`) are compared by **canonical locality name**
  after `IntentLocalities.resolve` (order-insensitive sets; an ambiguous alias such as "andheri" expands to
  both names). `unresolvedLocations` compared as a lower-cased set.

Content, ~70 cases (authored from the WS2 spec's problem list and the final review's slices; Archit may add real
queries later):

| Tag | ≈ | Examples |
|---|---|---|
| basics | 10 | "2bhk in powai under 40k", "private room in goregaon", "flatmate in andheri" |
| budget | 12 | floors, ceilings, ranges, "one and a half lakh", "40k", "2 lakh deposit 30k rent", "within 20 min of bkc, 25k", "15 min from powai for 25000", pincode not a budget |
| locality | 12 | "bandra kurla complex", "bkc", "powaii", "mansion" (not Sion), Andheri E/W ambiguity, "lower parel" vs "parel", two localities |
| exclusion | 6 | "not in powai", "anywhere but goregaon", "andheri nahi", "no smokers in andheri" (not an exclusion) |
| commute | 8 | "near bkc", "office in lower parel", "within 30 mins of dadar", "work at powai", reversed "10 min from kurla" |
| lifestyle / amenities | 6 | "non-smoker", "veg only", "pet friendly", "gym and parking", "female only", "couples ok" |
| hinglish | 6 | "single sharing room chahiye powai me budget 40k hai", "andheri me 1bhk 30k tak" |
| refinement (multi-turn) | 10 | "cheaper", "make it 30k", "not in powai" after "rooms in powai", "same but in andheri", "forget that, 2bhk in bandra 60k", "only verified listings" |

Must-pass: every case that mirrors an existing unit test (≈ 25) plus the arbiter cases.

### 4.2 Scoring

- Per case, per scored slot: `MATCH | MISMATCH(expected, actual)`. A case **passes** when every scored slot
  matches and, if `expectVerdict` is set, the arbiter verdict matches.
- Aggregates: case pass rate; per-slot accuracy over cases where the slot is expected non-null **or** the actual
  is non-null (a slot that is null on both sides everywhere is not counted); per-tag pass rate; verdict accuracy;
  `mustPass` failures listed by id.
- Offline gate thresholds (constants in `EvalThresholds`): all must-pass cases pass; case pass rate ≥ **0.85**;
  slot accuracy ≥ **0.90** for `locations`, `budgetMax`, `roomType`. Live: thresholds reported, never enforced.
- Cases tagged `known-gap` (a documented keyword-parser limitation) are excluded from every offline aggregate
  and threshold, listed separately in the report, and can never be `mustPass`. The live run still grades them.

### 4.3 Core (`com.flatmaite.eval`, main code, no Spring dependencies except Jackson)

- `GoldenCase` (record: id, tags, query, priorCaseId, mustPass, expectVerdict, `SearchIntent expected`) and
  `GoldenSet.load()` — reads the classpath JSON with the app's `ObjectMapper`; validates unique ids and that every
  `prior.case` names an earlier case; `expected` is built as a `SearchIntent` (omitted slots null).
- `IntentComparator.compare(expected, actual, nameOf)` → `List<SlotResult>`; `nameOf` maps a `LocationRef` to
  its canonical name (from `LocalityResolver.nameOf`, or the ref's own name when unresolved).
- `IntentArbiter` (new `@Component` in `search`): `Decision decide(String query, SearchIntent prior,
  BiFunction<String, SearchIntent, IntentLlm.Extraction> extract)` returning `(intent, verdict, mode, fresh)`.
  Contains exactly the block now inlined in `AiSearchController.search` (detector verdict → NEW drops prior →
  extract → AMBIGUOUS + `Mode.NEW` re-extracts without prior → fresh note). The controller calls it; the four
  `AiSearchControllerTest` cases move to `IntentArbiterTest` and the controller test keeps one wiring case.
- `IntentEvaluator.run(GoldenSet, Extractor)` where `Extractor` is `(query, prior) -> IntentArbiter.Decision`
  followed by `IntentLocalities.resolve`; produces `EvalReport`.
- `EvalReport`: per-case results + aggregates; `renderTable()` (fixed-width console table: id, tags, pass/fail,
  first mismatch), `toJson()`, `failures()`; `EvalThresholds.check(report)` → list of violated thresholds.

### 4.4 Offline gate — `IntentGoldenTest` (JUnit, pure)

Builds `LocalityResolver` over a Mockito `LocalityRepository` whose `findAll()` returns
`SeedLocalities.entities()` (§4.6), `KeywordIntentParser`, `MockLlms.MockIntentLlm`, `NewQueryDetector`,
`IntentArbiter`; runs every case; on any violated threshold fails with the rendered table and the failure diffs in
the assertion message. Target: < 1 s. Also `GoldenSetTest` (file loads; ids unique; priors resolve; every
`mustPass` case has at least one tag), `IntentComparatorTest`, `EvalReportTest`, `IntentArbiterTest`.

### 4.5 Live runner — `EvalRunner` under `@Profile("eval")`

- `application-eval.yml`: `spring.main.web-application-type: none` (mirrors `application-seed.yml`).
- Uses the real beans: `IntentLlm` (provider chosen by `FM_AI_PROVIDER`), `LocalityResolver` over the database
  gazetteer, `NewQueryDetector`, `IntentArbiter`. Refuses to start when `AiProviderConfig.useMock(...)` is true
  unless `EVAL_ALLOW_MOCK=true` (useful to smoke-test the runner itself).
- Calls `intentLlm.healthCheck()` first, then runs the cases sequentially with `EVAL_PACE_MS` (default 4500 ms)
  between provider calls; a provider exception is recorded as the case's failure (`error` field), not a crash.
  Optional `EVAL_TAGS=budget,commute` filter and `EVAL_LIMIT=n`.
- Output: table on stdout; `backend/target/eval/<provider>-<model>-<yyyyMMdd-HHmmss>.json` (report + run
  metadata: provider, model, case count, elapsed, calls, errors, and the provider's reported token usage when
  the ChatClient response exposes it, next to `promptOverheadTokens()` for comparison). Exit code 0.
- Command (run by Archit; the assistant reads the output):
  `cd backend && FM_AI_PROVIDER=google-genai GEMINI_API_KEY=… ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval`

### 4.6 `SeedLocalities` extraction

Move `SeedRunner`'s private `LocalitySeed[] LOCALITIES` and the `uuid("locality:" + name)` id rule into
`com.flatmaite.seed.SeedLocalities` with `static List<Locality> entities()` (deterministic ids, aliases, lat/lng,
rent band preserved). `SeedRunner.seedLocalities` uses it; no seed behaviour changes (existing integration tests
assert ≥ 35 localities and the WS2 admission behaviour).

### 4.7 Documentation

- README: "Intent eval" section — the offline gate, the live command, the env knobs, where reports land.
- `docs/eval/README.md`: how to add a case, what "omitted slot = expected null" means, how to mark must-pass.
- `docs/eval/<date>-<provider>-<model>.md`: summary of the first live run (pass rate, per-slot table, verdict
  accuracy, token usage vs estimate, notable failures) — written after Archit's run.

### 4.8 Degradation

| Situation | Behaviour |
|---|---|
| Golden JSON malformed / duplicate id / unknown prior | `GoldenSet.load()` throws with the case id; gate fails loudly |
| Provider error on a case (live) | recorded as that case's failure; run continues |
| Rate limit (429) | recorded; pacing is the only mitigation in WS3 |
| Mock provider in `eval` profile | refuses to start unless `EVAL_ALLOW_MOCK=true` |

## 5. Testing

| Test | Kind | Asserts |
|---|---|---|
| `GoldenSetTest` | pure | loads; unique ids; priors resolve to earlier cases; version 1 |
| `IntentComparatorTest` | pure | match/mismatch per slot type: scalar, enum, sets, nested commute/bhk, omitted-slot-means-null, ambiguous alias expands to two names |
| `EvalReportTest` | pure | aggregates, per-tag rates, threshold violations, table renders one line per case, JSON round-trips |
| `IntentArbiterTest` | pure | the four controller cases (AMBIGUOUS+NEW re-extracts with null prior; AMBIGUOUS+REFINE single call; NEW; no prior) |
| `AiSearchControllerTest` | pure | one wiring case: the arbiter's decision reaches `pipeline.search` with the fresh note |
| `IntentGoldenTest` | pure | thresholds hold for the keyword parser + arbiter |
| `SeedRunner` via existing ITs | Docker | unchanged |
| Live run | manual | report committed to `docs/eval/` |

## 6. Implementation order

1. `SeedLocalities` extraction (no behaviour change). 2. `IntentArbiter` extraction + tests. 3. Eval core
(`GoldenCase`/`GoldenSet`, `IntentComparator`, `EvalReport`, `EvalThresholds`, `IntentEvaluator`) with tests.
4. Golden set authoring (tags per §4.1) + `GoldenSetTest`. 5. `IntentGoldenTest` gate, tuned so the current parser
passes must-pass and thresholds (any parser bug found is fixed only if small; otherwise the case is tagged
`known-gap` and excluded from must-pass, listed in the report). 6. `EvalRunner` + `application-eval.yml`.
7. README + `docs/eval/README.md`. 8. Archit's live Gemini run → `docs/eval/<date>-google-genai-….md`.

## 7. Files

Create: `backend/src/main/java/com/flatmaite/eval/{GoldenCase,GoldenSet,IntentComparator,EvalReport,EvalThresholds,IntentEvaluator,EvalRunner}.java`,
`backend/src/main/java/com/flatmaite/search/IntentArbiter.java`, `backend/src/main/java/com/flatmaite/seed/SeedLocalities.java`,
`backend/src/main/resources/eval/intent-golden.json`, `backend/src/main/resources/application-eval.yml`,
`backend/src/test/java/com/flatmaite/eval/{GoldenSetTest,IntentComparatorTest,EvalReportTest,IntentGoldenTest}.java`,
`backend/src/test/java/com/flatmaite/search/IntentArbiterTest.java`, `docs/eval/README.md`.
Modify: `AiSearchController.java`, `AiSearchControllerTest.java`, `SeedRunner.java`, `README.md`.
No schema change; no new dependencies.
