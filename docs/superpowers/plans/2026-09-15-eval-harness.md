# Intent Eval Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure intent-extraction quality as a whole — a golden set of `query → expected SearchIntent` (with multi-turn arbiter verdicts), per-slot scoring, a pure JUnit gate over the keyword parser in `./mvnw verify`, and an `eval` Spring profile that runs the same set through the real provider and writes a report.

**Architecture:** One provider-agnostic core in `com.flatmaite.eval` (`GoldenSet` → `IntentEvaluator` → `IntentComparator` → `EvalReport`/`EvalThresholds`) driven by an `Extractor` function. Two entry points supply the extractor: `IntentGoldenTest` (offline: `SeedLocalities` gazetteer + `KeywordIntentParser` + `MockIntentLlm` + `IntentArbiter`, no Spring) and `EvalRunner` (`@Profile("eval")`: real beans via `SearchPipeline.extractIntent`, paced, JSON report). Two extractions from WS2 make this possible: the controller's new-vs-refine block becomes `IntentArbiter`, and the seed's private locality table becomes `SeedLocalities`.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI 1.1.8, Lombok, Jackson, JUnit 5 + AssertJ + Mockito, spring-test (`MockHttpServletRequest`). No new dependencies. No frontend change.

**Spec:** `docs/superpowers/specs/2026-09-15-eval-harness-design.md`

## Global Constraints

- Java 17; Maven wrapper — run every backend command from `backend/`. Docker Desktop must be running for the four Testcontainers classes (`LocationWideningIntegrationTest`, `SearchPipelineIntegrationTest`, `HybridRetrieverIntegrationTest`, `AuthFlowIntegrationTest`); everything else is pure.
- **No schema change, no Flyway migration, no new dependencies, no frontend change.**
- **Extraction behaviour is frozen in WS3.** `KeywordIntentParser`, `NewQueryDetector`, `LocationMentions`, `IntentLocalities`, `MockLlms`, `OpenAiLlms`, `RefinementHeuristics` keep their logic; the only permitted edits are visibility (`IntentLocalities` becomes `public`, its `resolve` `public static`) and the controller refactor in Task 2. A parser defect found by the golden set is reported and tagged `known-gap`, never fixed here.
- Package `com.flatmaite.eval` for the core and the runner; `IntentArbiter` lives in `com.flatmaite.search`; `SeedLocalities` in `com.flatmaite.seed`.
- Golden file: `backend/src/main/resources/eval/intent-golden.json`, top-level `{"version": 1, "cases": [...]}`. Case keys exactly: `id`, `tags`, `query`, `prior` (`null` or `{"case": "<earlier id>"}`), `mustPass` (boolean), `expectVerdict` (`null` | `"NEW"` | `"REFINE"` | `"AMBIGUOUS"`), `scoreIntent` (boolean, default `true`), `expect` (object). `expect` keys ⊆ `searchTarget, locations, excludeLocations, unresolvedLocations, commuteTo{place,maxMinutes}, budgetMin, budgetMax, maxDeposit, roomType, listingTypes, bhk{min,max}, furnished, genderPreference, couplesOk, verifiedOnly, amenities, lifestyle{smoking,pets,diet,quiet}`; `locations`/`excludeLocations`/`unresolvedLocations`/`amenities`/`listingTypes` are arrays of strings. **An omitted `expect` key means expected null/empty.**
- Scored slots, in this order (verbatim, 22): `searchTarget, locations, excludeLocations, unresolvedLocations, commuteTo.place, commuteTo.maxMinutes, budgetMin, budgetMax, maxDeposit, roomType, listingTypes, bhk.min, bhk.max, furnished, genderPreference, couplesOk, verifiedOnly, amenities, lifestyle.smoking, lifestyle.pets, lifestyle.diet, lifestyle.quiet`. Location slots compare lower-cased canonical-name sets; `commuteTo.place` compares lower-cased trimmed strings; list slots are order-insensitive and `null ≡ empty`.
- Thresholds (constants in `EvalThresholds`): `MIN_CASE_PASS_RATE = 0.85`, `MIN_SLOT_ACCURACY = 0.90`, `GATED_SLOTS = List.of("locations", "budgetMax", "roomType")`; plus every `mustPass` case must pass. Cases tagged `known-gap` are excluded from all offline aggregates and thresholds, reported under "known gaps", and may never be `mustPass`.
- Runner env knobs: `EVAL_PACE_MS` (default `4500`), `EVAL_TAGS` (comma list, default all), `EVAL_LIMIT` (default `0` = all), `EVAL_ALLOW_MOCK` (default `false`). Output `backend/target/eval/<provider>-<model>-<yyyyMMdd-HHmmss>.json`. Exit code always 0.
- Fresh-search note text unchanged: `"Started a fresh search — this read as a new request, not a tweak of the last one."` (now `IntentArbiter.FRESH_NOTE`).
- Seed identity preserved: `SeedLocalities.entities()` yields the same 38 localities, aliases, coordinates and ids as before — id = `UUID.nameUUIDFromBytes(("flatmaite:locality:" + name).getBytes(UTF_8))`.
- Commit messages: short imperative subject in the repo's style, ending with the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Branch: `eval-harness` (already created from `main` at `e19a48e`; the spec is its first commit).

---

### Task 1: `SeedLocalities` — lift the gazetteer out of `SeedRunner`

**Files:**
- Create: `backend/src/main/java/com/flatmaite/seed/SeedLocalities.java`
- Modify: `backend/src/main/java/com/flatmaite/seed/SeedRunner.java` (record `LocalitySeed` + array `LOCALITIES` at ~lines 129–171; `seedLocalities()` at ~289–297; every other use of `LOCALITIES`/`LocalitySeed` — grep for both)
- Test: `backend/src/test/java/com/flatmaite/seed/SeedLocalitiesTest.java`

**Interfaces:**
- Produces: `public record SeedLocalities.Seed(String name, double lat, double lng, String[] aliases, int rentBand)`; `public static final List<Seed> ALL` (38 entries, same order); `public static UUID id(String name)`; `public static List<Locality> entities()` (new instances each call, `setId(id(name))`, aliases copied).

- [ ] **Step 1: Write the failing test**

```java
package com.flatmaite.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The gazetteer is shared by the seed and the offline eval; its identity must not drift. */
class SeedLocalitiesTest {

  @Test
  void thirtyEightLocalities_withUniqueNames() {
    assertThat(SeedLocalities.ALL).hasSize(38);
    assertThat(SeedLocalities.ALL.stream().map(SeedLocalities.Seed::name).distinct()).hasSize(38);
  }

  @Test
  void ids_areTheSeedRunnersHistoricalFormula() {
    UUID expected = UUID.nameUUIDFromBytes("flatmaite:locality:Powai".getBytes(StandardCharsets.UTF_8));
    assertThat(SeedLocalities.id("Powai")).isEqualTo(expected);
  }

  @Test
  void entities_carryNameAliasesCoordinatesAndDeterministicIds() {
    List<Locality> all = SeedLocalities.entities();
    assertThat(all).hasSize(38);
    Locality bkc = all.stream().filter(l -> l.getName().equals("BKC")).findFirst().orElseThrow();
    assertThat(bkc.getId()).isEqualTo(SeedLocalities.id("BKC"));
    assertThat(bkc.getAliases()).contains("bandra kurla complex");
    assertThat(bkc.getLat()).isEqualTo(19.0653);
    assertThat(bkc.getLng()).isEqualTo(72.8693);
  }

  @Test
  void bothAndheris_aliasTheBareName() {
    long andheris =
        SeedLocalities.entities().stream()
            .filter(l -> List.of(l.getAliases()).contains("andheri"))
            .count();
    assertThat(andheris).isEqualTo(2);
  }

  @Test
  void entities_areFreshInstancesEachCall() {
    assertThat(SeedLocalities.entities().get(0)).isNotSameAs(SeedLocalities.entities().get(0));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=SeedLocalitiesTest`
Expected: compilation failure — `SeedLocalities` does not exist.

- [ ] **Step 3: Create `SeedLocalities`**

Move the 38 `new LocalitySeed(...)` lines verbatim (same order, same values) into `ALL`, renaming the constructor to `new Seed(...)`:

```java
package com.flatmaite.seed;

import com.flatmaite.listing.Locality;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The Mumbai gazetteer the seed writes and the offline intent eval reads. Ids are UUIDv3 over
 * a stable key so re-seeding upserts the same rows and the eval resolves the same ids.
 */
public final class SeedLocalities {

  /** rentBand = typical private-room rent midpoint (₹/month). */
  public record Seed(String name, double lat, double lng, String[] aliases, int rentBand) {}

  public static final List<Seed> ALL =
      List.of(
          new Seed("Andheri East", 19.1136, 72.8697, new String[] {"andheri east", "andheri"}, 22000),
          // ... the remaining 37 entries, moved verbatim from SeedRunner.LOCALITIES in the same order ...
          new Seed("Colaba", 18.9067, 72.8147, new String[] {"cuffe parade"}, 40000));

  private SeedLocalities() {}

  public static UUID id(String name) {
    return UUID.nameUUIDFromBytes(("flatmaite:locality:" + name).getBytes(StandardCharsets.UTF_8));
  }

  public static List<Locality> entities() {
    List<Locality> out = new ArrayList<>(ALL.size());
    for (Seed s : ALL) {
      Locality l =
          Locality.builder()
              .name(s.name())
              .lat(s.lat())
              .lng(s.lng())
              .aliases(Arrays.copyOf(s.aliases(), s.aliases().length))
              .build();
      l.setId(id(s.name()));
      out.add(l);
    }
    return out;
  }
}
```

(The `// ...` line above is an instruction to move the existing 37 lines, not a placeholder: the values already exist in `SeedRunner.java` lines ~133–170 and must be copied unchanged.)

- [ ] **Step 4: Point `SeedRunner` at it**

Delete `private record LocalitySeed(...)` and `private static final LocalitySeed[] LOCALITIES = {...}`. Replace `seedLocalities()` with:

```java
  private List<Locality> seedLocalities() {
    List<Locality> out = new ArrayList<>();
    for (Locality l : SeedLocalities.entities()) {
      out.add(localities.save(l));
    }
    return out;
  }
```

Then `grep -n 'LOCALITIES\|LocalitySeed' src/main/java/com/flatmaite/seed/SeedRunner.java` — every remaining use (rent-band lookups when building listings, for example) switches to `SeedLocalities.ALL` / `SeedLocalities.Seed` with identical semantics. Keep `SeedRunner.uuid(...)` for its other keys; the locality id must remain `uuid("locality:" + name)` ≡ `SeedLocalities.id(name)`.

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest=SeedLocalitiesTest` → 5 pass.
Run: `./mvnw -q test -Dtest=LocationWideningIntegrationTest` (Docker) → 4 pass (seeds through the new path; asserts ≥ 35 localities and resolves "Ram Mandir").

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flatmaite/seed/SeedLocalities.java backend/src/main/java/com/flatmaite/seed/SeedRunner.java backend/src/test/java/com/flatmaite/seed/SeedLocalitiesTest.java
git commit -m "$(cat <<'EOF'
Lift the locality gazetteer out of SeedRunner so the eval can resolve places without a database

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `IntentArbiter` — one referee for the controller and the eval

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/IntentArbiter.java`
- Modify: `backend/src/main/java/com/flatmaite/search/AiSearchController.java` (fields, constructor via Lombok, lines 36–38 and 63–81)
- Modify: `backend/src/test/java/com/flatmaite/search/AiSearchControllerTest.java` (keep one wiring case)
- Test: `backend/src/test/java/com/flatmaite/search/IntentArbiterTest.java`

**Interfaces:**
- Produces: `public class IntentArbiter` (`@Component`), `public static final String FRESH_NOTE`, `public record Decision(SearchIntent intent, NewQueryDetector.Verdict verdict, IntentLlm.Mode mode, boolean fresh) { public String note() }`, `public Decision decide(String query, SearchIntent prior, BiFunction<String, SearchIntent, IntentLlm.Extraction> extract)`.
- Consumes: `NewQueryDetector.decide`, `IntentLlm.Extraction`/`Mode`.

- [ ] **Step 1: Write the failing tests**

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.IntentLlm;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one referee for new-vs-refine. The AMBIGUOUS + Mode.NEW branch is the only place the model
 * can overrule the detector and discard a user's accumulated intent.
 */
class IntentArbiterTest {

  private static final SearchIntent PRIOR = SearchIntent.builder().budgetMax(20000).build();
  private static final SearchIntent REFINED = SearchIntent.builder().budgetMax(18000).build();
  private static final SearchIntent FRESH = SearchIntent.builder().budgetMax(30000).build();

  /** Records the prior handed to each call and answers from a queue. */
  private static final class RecordingExtractor implements BiFunction<String, SearchIntent, IntentLlm.Extraction> {
    final List<SearchIntent> priors = new ArrayList<>();
    final Deque<IntentLlm.Extraction> answers = new ArrayDeque<>();

    RecordingExtractor answer(SearchIntent intent, IntentLlm.Mode mode) {
      answers.add(new IntentLlm.Extraction(intent, mode));
      return this;
    }

    @Override
    public IntentLlm.Extraction apply(String query, SearchIntent prior) {
      priors.add(prior);
      return answers.pop();
    }
  }

  private NewQueryDetector detector;
  private IntentArbiter arbiter;

  @BeforeEach
  void setUp() {
    detector = mock(NewQueryDetector.class);
    arbiter = new IntentArbiter(detector);
  }

  @Test
  void ambiguous_modelSaysNew_reExtractsWithoutThePrior_andIsFresh() {
    when(detector.decide("flats in powai")).thenReturn(NewQueryDetector.Verdict.AMBIGUOUS);
    RecordingExtractor extract =
        new RecordingExtractor().answer(PRIOR, IntentLlm.Mode.NEW).answer(FRESH, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide("flats in powai", PRIOR, extract);

    assertThat(extract.priors).containsExactly(PRIOR, null);
    assertThat(d.intent()).isEqualTo(FRESH);
    assertThat(d.verdict()).isEqualTo(NewQueryDetector.Verdict.AMBIGUOUS);
    assertThat(d.mode()).isEqualTo(IntentLlm.Mode.NEW);
    assertThat(d.fresh()).isTrue();
    assertThat(d.note()).isEqualTo(IntentArbiter.FRESH_NOTE);
  }

  @Test
  void ambiguous_modelSaysRefine_extractsOnceWithThePrior_noNote() {
    when(detector.decide("cheaper please")).thenReturn(NewQueryDetector.Verdict.AMBIGUOUS);
    RecordingExtractor extract = new RecordingExtractor().answer(REFINED, IntentLlm.Mode.REFINE);

    IntentArbiter.Decision d = arbiter.decide("cheaper please", PRIOR, extract);

    assertThat(extract.priors).containsExactly(PRIOR);
    assertThat(d.intent()).isEqualTo(REFINED);
    assertThat(d.fresh()).isFalse();
    assertThat(d.note()).isNull();
  }

  @Test
  void detectorSaysNew_extractsOnceWithoutThePrior_andIsFresh() {
    String q = "forget that, single sharing room in goregaon 20k";
    when(detector.decide(q)).thenReturn(NewQueryDetector.Verdict.NEW);
    RecordingExtractor extract = new RecordingExtractor().answer(FRESH, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide(q, PRIOR, extract);

    assertThat(extract.priors).containsExactly((SearchIntent) null);
    assertThat(d.verdict()).isEqualTo(NewQueryDetector.Verdict.NEW);
    assertThat(d.fresh()).isTrue();
  }

  @Test
  void detectorSaysRefine_extractsOnceWithThePrior() {
    when(detector.decide("make it 18k")).thenReturn(NewQueryDetector.Verdict.REFINE);
    RecordingExtractor extract = new RecordingExtractor().answer(REFINED, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide("make it 18k", PRIOR, extract);

    assertThat(extract.priors).containsExactly(PRIOR);
    assertThat(d.intent()).isEqualTo(REFINED);
    assertThat(d.fresh()).isFalse();
  }

  @Test
  void noPrior_detectorNeverConsulted_singleExtraction_notFresh() {
    RecordingExtractor extract = new RecordingExtractor().answer(FRESH, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide("single sharing room in goregaon 20k", null, extract);

    verifyNoInteractions(detector);
    assertThat(extract.priors).containsExactly((SearchIntent) null);
    assertThat(d.verdict()).isEqualTo(NewQueryDetector.Verdict.NEW);
    assertThat(d.fresh()).isFalse();
    assertThat(d.note()).isNull();
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=IntentArbiterTest` → compilation failure (`IntentArbiter` missing).

- [ ] **Step 3: Create `IntentArbiter`**

```java
package com.flatmaite.search;

import com.flatmaite.ai.IntentLlm;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one referee for "is this follow-up a new search or a tweak of the current one". The lexical
 * detector rules the clear cases; only for an AMBIGUOUS follow-up does the model's read of the
 * message (its {@code mode}) break the tie. A new request must not inherit the previous search's
 * constraints — a stale locality silently zeroes out results. Shared by the HTTP controller and the
 * intent eval so both judge exactly the same way.
 */
@Component
@RequiredArgsConstructor
public class IntentArbiter {

  public static final String FRESH_NOTE =
      "Started a fresh search — this read as a new request, not a tweak of the last one.";

  /** {@code fresh} = a prior existed and was discarded; only then does the response carry a note. */
  public record Decision(
      SearchIntent intent, NewQueryDetector.Verdict verdict, IntentLlm.Mode mode, boolean fresh) {
    public String note() {
      return fresh ? FRESH_NOTE : null;
    }
  }

  private final NewQueryDetector detector;

  /**
   * @param extract runs the extraction for (query, prior-or-null); called once, or twice when an
   *     ambiguous follow-up is judged NEW by the model. The AMBIGUOUS + NEW path bills two model
   *     calls by design — the fresh extraction cannot reuse a result that was merged onto the prior.
   */
  public Decision decide(
      String query, SearchIntent prior, BiFunction<String, SearchIntent, IntentLlm.Extraction> extract) {
    if (prior == null) {
      IntentLlm.Extraction e = extract.apply(query, null);
      return new Decision(e.intent(), NewQueryDetector.Verdict.NEW, e.mode(), false);
    }
    NewQueryDetector.Verdict verdict = detector.decide(query);
    if (verdict == NewQueryDetector.Verdict.NEW) {
      IntentLlm.Extraction e = extract.apply(query, null);
      return new Decision(e.intent(), verdict, e.mode(), true);
    }
    IntentLlm.Extraction e = extract.apply(query, prior);
    if (verdict == NewQueryDetector.Verdict.AMBIGUOUS && e.mode() == IntentLlm.Mode.NEW) {
      IntentLlm.Extraction fresh = extract.apply(query, null);
      return new Decision(fresh.intent(), verdict, e.mode(), true);
    }
    return new Decision(e.intent(), verdict, e.mode(), false);
  }
}
```

- [ ] **Step 4: Route the controller through it**

In `AiSearchController`: remove `FRESH_NOTE` and the `NewQueryDetector newQueryDetector` field; add `private final IntentArbiter arbiter;` (keep the field order `pipeline, sessions, usage, rateLimiter, arbiter` — Lombok's constructor follows it). Replace lines 63–81 (from the `// One referee` comment through `SearchIntent intent = extraction.intent();`) with:

```java
    IntentArbiter.Decision decision =
        arbiter.decide(body.query(), prior, (q, p) -> pipeline.extractIntent(q, p, userId, anonKey));
    SearchIntent intent = decision.intent();
    String note = decision.note();
```

Remove the now-unused `IntentLlm` import if nothing else in the file uses it.

- [ ] **Step 5: Slim the controller test to one wiring case**

In `AiSearchControllerTest`: construct `controller = new AiSearchController(pipeline, sessions, usage, rateLimiter, new IntentArbiter(detector));` (keep `detector` as the Mockito mock). Keep only `ambiguous_modelOverrulesTheDetector_reExtractsFresh_andNotesIt`, asserting `noteCaptor.getValue()` equals `IntentArbiter.FRESH_NOTE`; delete the other three tests (they now live in `IntentArbiterTest`). Update the class javadoc to say it proves the arbiter's decision reaches `pipeline.search` with the note.

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest=IntentArbiterTest,AiSearchControllerTest` → 5 + 1 pass.
Run: `./mvnw -q test -Dtest=SearchPipelineIntegrationTest` (Docker) → 7 pass (HTTP path still works through the arbiter bean).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/IntentArbiter.java backend/src/main/java/com/flatmaite/search/AiSearchController.java backend/src/test/java/com/flatmaite/search/IntentArbiterTest.java backend/src/test/java/com/flatmaite/search/AiSearchControllerTest.java
git commit -m "$(cat <<'EOF'
Move the new-vs-refine referee out of the controller so the eval judges follow-ups the same way

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Golden cases and the slot comparator

**Files:**
- Create: `backend/src/main/java/com/flatmaite/eval/GoldenCase.java`, `backend/src/main/java/com/flatmaite/eval/GoldenSet.java`, `backend/src/main/java/com/flatmaite/eval/IntentComparator.java`
- Create: `backend/src/test/resources/eval/sample-golden.json`
- Test: `backend/src/test/java/com/flatmaite/eval/GoldenSetTest.java`, `backend/src/test/java/com/flatmaite/eval/IntentComparatorTest.java`

**Interfaces:**
- Produces: `public record GoldenCase(String id, List<String> tags, String query, String priorCase, boolean mustPass, NewQueryDetector.Verdict expectVerdict, boolean scoreIntent, SearchIntent expected)` with `boolean knownGap()`; `public final class GoldenSet` with `static GoldenSet load()` (classpath `eval/intent-golden.json`), `static GoldenSet parse(String json)`, `int version()`, `List<GoldenCase> cases()`, `SearchIntent priorOf(GoldenCase c)` (expected intent of the prior case, or null), `GoldenSet filter(Set<String> tags, int limit)` (keeps every case's prior resolvable); `public final class IntentComparator` with `public static final List<String> SLOTS` (the 22 names), `public record SlotResult(String slot, boolean match, String expected, String actual)`, `static List<SlotResult> compare(SearchIntent expected, SearchIntent actual, Function<LocationRef, String> actualNameOf)`.

- [ ] **Step 1: Sample file for the tests**

`backend/src/test/resources/eval/sample-golden.json`:

```json
{
  "version": 1,
  "cases": [
    {
      "id": "sample-first",
      "tags": ["basics"],
      "query": "2bhk in powai under 40k",
      "prior": null,
      "mustPass": true,
      "expectVerdict": null,
      "expect": {
        "searchTarget": "PROPERTIES",
        "locations": ["Powai"],
        "bhk": {"min": 2, "max": 2},
        "budgetMax": 40000
      }
    },
    {
      "id": "sample-followup",
      "tags": ["refinement"],
      "query": "make it 30k",
      "prior": {"case": "sample-first"},
      "mustPass": false,
      "expectVerdict": "REFINE",
      "expect": {
        "searchTarget": "PROPERTIES",
        "locations": ["Powai"],
        "bhk": {"min": 2, "max": 2},
        "budgetMax": 30000
      }
    },
    {
      "id": "sample-verdict-only",
      "tags": ["arbiter", "known-gap"],
      "query": "flats in goregaon",
      "prior": {"case": "sample-first"},
      "mustPass": false,
      "expectVerdict": "AMBIGUOUS",
      "scoreIntent": false,
      "expect": {}
    }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoldenSetTest {

  private static String sample() throws IOException {
    try (var in = GoldenSetTest.class.getResourceAsStream("/eval/sample-golden.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void parsesCases_expectedIntent_andPriors() throws IOException {
    GoldenSet set = GoldenSet.parse(sample());

    assertThat(set.version()).isEqualTo(1);
    assertThat(set.cases()).extracting(GoldenCase::id)
        .containsExactly("sample-first", "sample-followup", "sample-verdict-only");
    GoldenCase first = set.cases().get(0);
    assertThat(first.expected().locations()).extracting(SearchIntent.LocationRef::name).containsExactly("Powai");
    assertThat(first.expected().bhk().min()).isEqualTo(2);
    assertThat(first.expected().budgetMax()).isEqualTo(40000);
    assertThat(first.expected().roomType()).isNull(); // omitted = null
    assertThat(first.scoreIntent()).isTrue();

    GoldenCase followup = set.cases().get(1);
    assertThat(followup.expectVerdict()).isEqualTo(NewQueryDetector.Verdict.REFINE);
    assertThat(set.priorOf(followup)).isEqualTo(first.expected());
    assertThat(set.priorOf(first)).isNull();

    GoldenCase verdictOnly = set.cases().get(2);
    assertThat(verdictOnly.scoreIntent()).isFalse();
    assertThat(verdictOnly.knownGap()).isTrue();
  }

  @Test
  void duplicateId_isRejected() {
    String json = """
        {"version":1,"cases":[
          {"id":"a","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}},
          {"id":"a","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("a");
  }

  @Test
  void priorMustNameAnEarlierCase() {
    String json = """
        {"version":1,"cases":[
          {"id":"b","tags":[],"query":"q","prior":{"case":"later"},"mustPass":false,"expectVerdict":null,"expect":{}},
          {"id":"later","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("b");
  }

  @Test
  void unknownExpectKey_isRejected_withTheCaseId() {
    String json = """
        {"version":1,"cases":[
          {"id":"typo","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{"budgetmax":1}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("typo")
        .hasMessageContaining("budgetmax");
  }

  @Test
  void knownGap_mayNotBeMustPass() {
    String json = """
        {"version":1,"cases":[
          {"id":"gap","tags":["known-gap"],"query":"q","prior":null,"mustPass":true,"expectVerdict":null,"expect":{}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("gap");
  }

  @Test
  void filter_keepsPriorsResolvable() throws IOException {
    GoldenSet filtered = GoldenSet.parse(sample()).filter(Set.of("refinement"), 0);

    assertThat(filtered.cases()).extracting(GoldenCase::id).containsExactly("sample-followup");
    assertThat(filtered.priorOf(filtered.cases().get(0)).budgetMax()).isEqualTo(40000);
    assertThat(GoldenSet.parse(sample()).filter(Set.of(), 2).cases()).hasSize(2);
  }
}
```

```java
package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.eval.IntentComparator.SlotResult;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class IntentComparatorTest {

  private static final UUID POWAI = UUID.randomUUID();
  private static final UUID ANDHERI_E = UUID.randomUUID();
  private static final UUID ANDHERI_W = UUID.randomUUID();
  private static final Function<LocationRef, String> NAME_OF =
      ref -> Map.of(POWAI, "Powai", ANDHERI_E, "Andheri East", ANDHERI_W, "Andheri West").getOrDefault(ref.localityId(), ref.name());

  private static Map<String, SlotResult> bySlot(List<SlotResult> results) {
    return results.stream().collect(java.util.stream.Collectors.toMap(SlotResult::slot, r -> r));
  }

  @Test
  void everySlotIsReported_inOrder() {
    List<SlotResult> r = IntentComparator.compare(SearchIntent.builder().build(), SearchIntent.builder().build(), NAME_OF);
    assertThat(r).extracting(SlotResult::slot).containsExactlyElementsOf(IntentComparator.SLOTS);
    assertThat(r).allMatch(SlotResult::match);
  }

  @Test
  void scalarMismatch_carriesBothValues() {
    SearchIntent expected = SearchIntent.builder().budgetMax(25000).build();
    SearchIntent actual = SearchIntent.builder().budgetMin(25000).build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("budgetMax").match()).isFalse();
    assertThat(r.get("budgetMax").expected()).isEqualTo("25000");
    assertThat(r.get("budgetMax").actual()).isEqualTo("null");
    assertThat(r.get("budgetMin").match()).isFalse(); // omitted expectation means null
  }

  @Test
  void locations_compareByCanonicalName_orderInsensitive() {
    SearchIntent expected = SearchIntent.builder()
        .locations(List.of(new LocationRef("Andheri West", null), new LocationRef("Andheri East", null))).build();
    SearchIntent actual = SearchIntent.builder()
        .locations(List.of(new LocationRef("Andheri East", ANDHERI_E), new LocationRef("Andheri West", ANDHERI_W))).build();
    assertThat(bySlot(IntentComparator.compare(expected, actual, NAME_OF)).get("locations").match()).isTrue();
  }

  @Test
  void locations_missingOne_isAMismatch_renderedSorted() {
    SearchIntent expected = SearchIntent.builder()
        .locations(List.of(new LocationRef("Andheri East", null), new LocationRef("Andheri West", null))).build();
    SearchIntent actual = SearchIntent.builder().locations(List.of(new LocationRef("Andheri East", ANDHERI_E))).build();
    SlotResult slot = bySlot(IntentComparator.compare(expected, actual, NAME_OF)).get("locations");
    assertThat(slot.match()).isFalse();
    assertThat(slot.expected()).isEqualTo("andheri east, andheri west");
    assertThat(slot.actual()).isEqualTo("andheri east");
  }

  @Test
  void commutePlace_isCaseInsensitive_andMinutesCompareExactly() {
    SearchIntent expected = SearchIntent.builder().commuteTo(new CommuteTo("bkc", null, 20)).build();
    SearchIntent actual = SearchIntent.builder().commuteTo(new CommuteTo("BKC", UUID.randomUUID(), 30)).build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("commuteTo.place").match()).isTrue();
    assertThat(r.get("commuteTo.maxMinutes").match()).isFalse();
  }

  @Test
  void listSlots_nullEqualsEmpty_andIgnoreOrder() {
    SearchIntent expected = SearchIntent.builder().amenities(List.of("parking", "gym")).listingTypes(null).build();
    SearchIntent actual = SearchIntent.builder().amenities(List.of("Gym", "Parking")).listingTypes(List.of()).build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("amenities").match()).isTrue();
    assertThat(r.get("listingTypes").match()).isTrue();
    SearchIntent typed = SearchIntent.builder().listingTypes(List.of(ListingType.PRIVATE_ROOM)).build();
    assertThat(bySlot(IntentComparator.compare(typed, actual, NAME_OF)).get("listingTypes").actual()).isEqualTo("");
  }

  @Test
  void nestedSlots_areNullSafe() {
    SearchIntent expected = SearchIntent.builder()
        .bhk(new SearchIntent.BhkRange(2, 2))
        .lifestyle(SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").build()).build();
    SearchIntent actual = SearchIntent.builder().build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("bhk.min").match()).isFalse();
    assertThat(r.get("bhk.max").expected()).isEqualTo("2");
    assertThat(r.get("lifestyle.smoking").match()).isFalse();
    assertThat(r.get("lifestyle.pets").match()).isTrue();
  }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q test -Dtest=GoldenSetTest,IntentComparatorTest` → compilation failure.

- [ ] **Step 4: Implement `GoldenCase`, `GoldenSet`, `IntentComparator`**

```java
package com.flatmaite.eval;

import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.util.List;

/** One golden case: a query (optionally a follow-up to an earlier case) and the intent it should yield. */
public record GoldenCase(
    String id,
    List<String> tags,
    String query,
    String priorCase,
    boolean mustPass,
    NewQueryDetector.Verdict expectVerdict,
    boolean scoreIntent,
    SearchIntent expected) {

  public static final String KNOWN_GAP = "known-gap";

  public boolean knownGap() {
    return tags.contains(KNOWN_GAP);
  }
}
```

```java
package com.flatmaite.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The golden set, validated on load: unique ids, priors that name an earlier case, only known
 * {@code expect} keys (a typo would otherwise read as "expected null" and fail confusingly), and
 * no must-pass case tagged known-gap.
 */
public final class GoldenSet {

  public static final String RESOURCE = "eval/intent-golden.json";

  private static final Set<String> EXPECT_KEYS =
      Set.of("searchTarget", "locations", "excludeLocations", "unresolvedLocations", "commuteTo",
          "budgetMin", "budgetMax", "maxDeposit", "roomType", "listingTypes", "bhk", "furnished",
          "genderPreference", "couplesOk", "verifiedOnly", "amenities", "lifestyle");
  private static final Set<String> COMMUTE_KEYS = Set.of("place", "maxMinutes");
  private static final Set<String> BHK_KEYS = Set.of("min", "max");
  private static final Set<String> LIFESTYLE_KEYS = Set.of("smoking", "pets", "diet", "quiet");
  private static final Set<String> NAME_LIST_KEYS = Set.of("locations", "excludeLocations");

  private final int version;
  private final List<GoldenCase> all;
  private final List<GoldenCase> selected;
  private final Map<String, GoldenCase> byId;

  private GoldenSet(int version, List<GoldenCase> all, List<GoldenCase> selected) {
    this.version = version;
    this.all = List.copyOf(all);
    this.selected = List.copyOf(selected);
    Map<String, GoldenCase> index = new HashMap<>();
    for (GoldenCase c : all) {
      index.put(c.id(), c);
    }
    this.byId = Map.copyOf(index);
  }

  public static GoldenSet load() {
    try (InputStream in = GoldenSet.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("golden set not on the classpath: " + RESOURCE);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
  }

  public static GoldenSet parse(String json) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      JsonNode root = mapper.readTree(json);
      int version = root.path("version").asInt(-1);
      if (version != 1) {
        throw new IllegalStateException("golden set version must be 1, was " + version);
      }
      List<GoldenCase> cases = new ArrayList<>();
      Set<String> seen = new java.util.HashSet<>();
      for (JsonNode node : root.path("cases")) {
        GoldenCase c = readCase(mapper, node, seen);
        seen.add(c.id());
        cases.add(c);
      }
      return new GoldenSet(version, cases, cases);
    } catch (IOException e) {
      throw new IllegalStateException("golden set is not valid JSON", e);
    }
  }

  private static GoldenCase readCase(ObjectMapper mapper, JsonNode node, Set<String> earlier) throws IOException {
    String id = node.path("id").asText(null);
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("golden case without an id: " + node);
    }
    if (earlier.contains(id)) {
      throw new IllegalStateException("duplicate golden case id: " + id);
    }
    List<String> tags = new ArrayList<>();
    node.path("tags").forEach(t -> tags.add(t.asText()));
    String query = node.path("query").asText(null);
    if (query == null) {
      throw new IllegalStateException("golden case " + id + " has no query");
    }
    String priorCase = null;
    JsonNode prior = node.path("prior");
    if (!prior.isMissingNode() && !prior.isNull()) {
      priorCase = prior.path("case").asText(null);
      if (priorCase == null || !earlier.contains(priorCase)) {
        throw new IllegalStateException("golden case " + id + ": prior must name an earlier case, was " + priorCase);
      }
    }
    boolean mustPass = node.path("mustPass").asBoolean(false);
    boolean scoreIntent = node.path("scoreIntent").asBoolean(true);
    NewQueryDetector.Verdict verdict = null;
    JsonNode v = node.path("expectVerdict");
    if (!v.isMissingNode() && !v.isNull()) {
      verdict = NewQueryDetector.Verdict.valueOf(v.asText());
    }
    if (tags.contains(GoldenCase.KNOWN_GAP) && mustPass) {
      throw new IllegalStateException("golden case " + id + " is known-gap and must-pass at once");
    }
    JsonNode expect = node.path("expect");
    if (!expect.isObject()) {
      throw new IllegalStateException("golden case " + id + " needs an expect object");
    }
    validateKeys(id, expect, EXPECT_KEYS, "expect");
    validateKeys(id, expect.path("commuteTo"), COMMUTE_KEYS, "expect.commuteTo");
    validateKeys(id, expect.path("bhk"), BHK_KEYS, "expect.bhk");
    validateKeys(id, expect.path("lifestyle"), LIFESTYLE_KEYS, "expect.lifestyle");
    SearchIntent expected = mapper.treeToValue(toIntentNode(mapper, (ObjectNode) expect), SearchIntent.class);
    return new GoldenCase(id, List.copyOf(tags), query, priorCase, mustPass, verdict, scoreIntent, expected);
  }

  /** Golden files write place names as strings; the intent wants LocationRef objects. */
  private static ObjectNode toIntentNode(ObjectMapper mapper, ObjectNode expect) {
    ObjectNode out = expect.deepCopy();
    for (String key : NAME_LIST_KEYS) {
      if (out.has(key) && out.get(key).isArray()) {
        ArrayNode refs = mapper.createArrayNode();
        for (JsonNode name : out.get(key)) {
          refs.add(mapper.createObjectNode().put("name", name.asText()));
        }
        out.set(key, refs);
      }
    }
    return out;
  }

  private static void validateKeys(String id, JsonNode obj, Set<String> allowed, String where) {
    if (!obj.isObject()) {
      return;
    }
    for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!allowed.contains(key)) {
        throw new IllegalStateException("golden case " + id + ": unknown key '" + key + "' in " + where);
      }
    }
  }

  public int version() {
    return version;
  }

  /** The cases to run (all of them, or the filtered selection). */
  public List<GoldenCase> cases() {
    return selected;
  }

  /** The expected intent of the case's prior, or null for a first turn. */
  public SearchIntent priorOf(GoldenCase c) {
    return c.priorCase() == null ? null : byId.get(c.priorCase()).expected();
  }

  /** Empty tags = every case; limit 0 = no limit. Priors stay resolvable through the full index. */
  public GoldenSet filter(Set<String> tags, int limit) {
    List<GoldenCase> out = new ArrayList<>();
    for (GoldenCase c : all) {
      if (!tags.isEmpty() && c.tags().stream().noneMatch(tags::contains)) {
        continue;
      }
      out.add(c);
      if (limit > 0 && out.size() >= limit) {
        break;
      }
    }
    return new GoldenSet(version, all, out);
  }
}
```

```java
package com.flatmaite.eval;

import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/** Slot-by-slot comparison of an expected and an actual intent. Never throws on nulls. */
public final class IntentComparator {

  public static final List<String> SLOTS =
      List.of("searchTarget", "locations", "excludeLocations", "unresolvedLocations", "commuteTo.place",
          "commuteTo.maxMinutes", "budgetMin", "budgetMax", "maxDeposit", "roomType", "listingTypes",
          "bhk.min", "bhk.max", "furnished", "genderPreference", "couplesOk", "verifiedOnly", "amenities",
          "lifestyle.smoking", "lifestyle.pets", "lifestyle.diet", "lifestyle.quiet");

  public record SlotResult(String slot, boolean match, String expected, String actual) {}

  private IntentComparator() {}

  /** @param actualNameOf canonical name of an actual ref (resolver lookup; falls back to the ref's name). */
  public static List<SlotResult> compare(SearchIntent expected, SearchIntent actual, Function<LocationRef, String> actualNameOf) {
    List<SlotResult> out = new ArrayList<>(SLOTS.size());
    out.add(scalar("searchTarget", expected.searchTarget(), actual.searchTarget()));
    out.add(set("locations", names(expected.locations(), LocationRef::name), names(actual.locations(), actualNameOf)));
    out.add(set("excludeLocations", names(expected.excludeLocations(), LocationRef::name), names(actual.excludeLocations(), actualNameOf)));
    out.add(set("unresolvedLocations", strings(expected.unresolvedLocations()), strings(actual.unresolvedLocations())));
    out.add(text("commuteTo.place",
        expected.commuteTo() == null ? null : expected.commuteTo().place(),
        actual.commuteTo() == null ? null : actual.commuteTo().place()));
    out.add(scalar("commuteTo.maxMinutes",
        expected.commuteTo() == null ? null : expected.commuteTo().maxMinutes(),
        actual.commuteTo() == null ? null : actual.commuteTo().maxMinutes()));
    out.add(scalar("budgetMin", expected.budgetMin(), actual.budgetMin()));
    out.add(scalar("budgetMax", expected.budgetMax(), actual.budgetMax()));
    out.add(scalar("maxDeposit", expected.maxDeposit(), actual.maxDeposit()));
    out.add(scalar("roomType", expected.roomType(), actual.roomType()));
    out.add(set("listingTypes", enums(expected.listingTypes()), enums(actual.listingTypes())));
    out.add(scalar("bhk.min", expected.bhk() == null ? null : expected.bhk().min(), actual.bhk() == null ? null : actual.bhk().min()));
    out.add(scalar("bhk.max", expected.bhk() == null ? null : expected.bhk().max(), actual.bhk() == null ? null : actual.bhk().max()));
    out.add(scalar("furnished", expected.furnished(), actual.furnished()));
    out.add(scalar("genderPreference", expected.genderPreference(), actual.genderPreference()));
    out.add(scalar("couplesOk", expected.couplesOk(), actual.couplesOk()));
    out.add(scalar("verifiedOnly", expected.verifiedOnly(), actual.verifiedOnly()));
    out.add(set("amenities", strings(expected.amenities()), strings(actual.amenities())));
    SearchIntent.Lifestyle el = expected.lifestyle();
    SearchIntent.Lifestyle al = actual.lifestyle();
    out.add(scalar("lifestyle.smoking", el == null ? null : el.smoking(), al == null ? null : al.smoking()));
    out.add(scalar("lifestyle.pets", el == null ? null : el.pets(), al == null ? null : al.pets()));
    out.add(scalar("lifestyle.diet", el == null ? null : el.diet(), al == null ? null : al.diet()));
    out.add(scalar("lifestyle.quiet", el == null ? null : el.quiet(), al == null ? null : al.quiet()));
    return out;
  }

  private static SlotResult scalar(String slot, Object expected, Object actual) {
    return new SlotResult(slot, Objects.equals(expected, actual), String.valueOf(expected), String.valueOf(actual));
  }

  private static SlotResult text(String slot, String expected, String actual) {
    return new SlotResult(slot, norm(expected).equals(norm(actual)), String.valueOf(expected), String.valueOf(actual));
  }

  private static SlotResult set(String slot, TreeSet<String> expected, TreeSet<String> actual) {
    return new SlotResult(slot, expected.equals(actual), String.join(", ", expected), String.join(", ", actual));
  }

  private static TreeSet<String> names(List<LocationRef> refs, Function<LocationRef, String> nameOf) {
    TreeSet<String> out = new TreeSet<>();
    if (refs != null) {
      for (LocationRef r : refs) {
        String n = nameOf.apply(r);
        out.add(norm(n == null ? r.name() : n));
      }
    }
    return out;
  }

  private static TreeSet<String> strings(Collection<String> values) {
    TreeSet<String> out = new TreeSet<>();
    if (values != null) {
      values.forEach(v -> out.add(norm(v)));
    }
    return out;
  }

  private static TreeSet<String> enums(Collection<? extends Enum<?>> values) {
    TreeSet<String> out = new TreeSet<>();
    if (values != null) {
      values.forEach(v -> out.add(v.name()));
    }
    return out;
  }

  private static String norm(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest=GoldenSetTest,IntentComparatorTest` → 6 + 7 pass. (If `SearchIntent.Lifestyle.builder()` is unavailable, use the record constructor with nulls — check `SearchIntent.java:55-68` first; it is annotated `@Builder`.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flatmaite/eval backend/src/test/java/com/flatmaite/eval backend/src/test/resources/eval/sample-golden.json
git commit -m "$(cat <<'EOF'
Add golden intent cases and a slot-by-slot intent comparator

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Report, thresholds and the evaluator loop

**Files:**
- Create: `backend/src/main/java/com/flatmaite/eval/CaseResult.java`, `backend/src/main/java/com/flatmaite/eval/EvalReport.java`, `backend/src/main/java/com/flatmaite/eval/EvalThresholds.java`, `backend/src/main/java/com/flatmaite/eval/IntentEvaluator.java`
- Test: `backend/src/test/java/com/flatmaite/eval/EvalReportTest.java`, `backend/src/test/java/com/flatmaite/eval/IntentEvaluatorTest.java`

**Interfaces:**
- Produces: `public record CaseResult(GoldenCase golden, List<SlotResult> slots, NewQueryDetector.Verdict verdict, String error, long millis)` with `passed()`, `knownGap()`, `firstMismatch()`; `public final class EvalReport` (`results()`, `gated()`, `casePassRate()`, `slotAccuracy()`, `tagPassRate()`, `verdictAccuracy()`, `failures()`, `mustPassFailures()`, `knownGaps()`, `renderTable()`, `toJson(ObjectMapper, Map<String,Object> meta)`); `public final class EvalThresholds` (`MIN_CASE_PASS_RATE`, `MIN_SLOT_ACCURACY`, `GATED_SLOTS`, `static List<String> violations(EvalReport)`); `public final class IntentEvaluator` with `@FunctionalInterface interface Extractor { IntentArbiter.Decision extract(String query, SearchIntent prior) throws Exception; }` and `static EvalReport run(GoldenSet set, Extractor extractor, Function<LocationRef,String> nameOf, Consumer<CaseResult> onCase)`.
- Consumes: Task 2 `IntentArbiter.Decision`; Task 3 `GoldenSet`, `IntentComparator`.

- [ ] **Step 1: Write the failing tests**

```java
package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flatmaite.eval.IntentComparator.SlotResult;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalReportTest {

  private static GoldenCase golden(String id, boolean mustPass, NewQueryDetector.Verdict verdict, String... tags) {
    return new GoldenCase(id, List.of(tags), "q " + id, null, mustPass, verdict, true, SearchIntent.builder().build());
  }

  private static SlotResult ok(String slot) {
    return new SlotResult(slot, true, "x", "x");
  }

  private static SlotResult bad(String slot) {
    return new SlotResult(slot, false, "x", "y");
  }

  /** Three gated cases (2 pass, 1 fail on budgetMax), one known gap, one verdict-only case. */
  private static EvalReport sample() {
    return new EvalReport(List.of(
        new CaseResult(golden("a", true, null, "basics"), List.of(ok("locations"), ok("budgetMax")), NewQueryDetector.Verdict.NEW, null, 1),
        new CaseResult(golden("b", false, null, "basics", "budget"), List.of(ok("locations"), bad("budgetMax")), NewQueryDetector.Verdict.NEW, null, 1),
        new CaseResult(golden("c", false, NewQueryDetector.Verdict.REFINE, "refinement"), List.of(ok("locations")), NewQueryDetector.Verdict.REFINE, null, 1),
        new CaseResult(golden("gap", false, null, "budget", GoldenCase.KNOWN_GAP), List.of(bad("budgetMax")), NewQueryDetector.Verdict.NEW, null, 1),
        new CaseResult(golden("v", false, NewQueryDetector.Verdict.NEW, "arbiter"), List.of(), NewQueryDetector.Verdict.REFINE, null, 1)));
  }

  @Test
  void passRates_excludeKnownGaps() {
    EvalReport r = sample();
    assertThat(r.gated()).extracting(cr -> cr.golden().id()).containsExactly("a", "b", "c", "v");
    assertThat(r.casePassRate()).isEqualTo(2.0 / 4); // a, c pass; b (slot) and v (verdict) fail
    assertThat(r.knownGaps()).extracting(cr -> cr.golden().id()).containsExactly("gap");
  }

  @Test
  void slotAccuracy_countsOnlySlotsPresentOnEitherSide() {
    Map<String, Double> acc = sample().slotAccuracy();
    assertThat(acc.get("locations")).isEqualTo(1.0);
    assertThat(acc.get("budgetMax")).isEqualTo(0.5); // a ok, b bad (gap excluded)
    assertThat(acc).doesNotContainKey("roomType"); // never present
  }

  @Test
  void tagAndVerdictRates() {
    EvalReport r = sample();
    assertThat(r.tagPassRate().get("basics")).isEqualTo(0.5);
    assertThat(r.tagPassRate().get("budget")).isEqualTo(0.0);
    assertThat(r.verdictAccuracy()).isEqualTo(0.5); // c right, v wrong
  }

  @Test
  void failuresAndMustPass() {
    EvalReport r = sample();
    assertThat(r.failures()).extracting(cr -> cr.golden().id()).containsExactly("b", "v");
    assertThat(r.mustPassFailures()).isEmpty();
    assertThat(r.results().get(1).firstMismatch()).isEqualTo("budgetMax: expected x, got y");
    assertThat(r.results().get(4).firstMismatch()).isEqualTo("verdict: expected NEW, got REFINE");
  }

  @Test
  void thresholds_reportEveryViolation() {
    List<String> v = EvalThresholds.violations(sample());
    assertThat(v).anyMatch(s -> s.contains("case pass rate"));
    assertThat(v).anyMatch(s -> s.contains("budgetMax"));
    assertThat(v).noneMatch(s -> s.contains("locations"));
  }

  @Test
  void tableAndJson() {
    EvalReport r = sample();
    String table = r.renderTable();
    assertThat(table.lines()).hasSize(6); // header + 5 cases
    assertThat(table).contains("PASS").contains("FAIL").contains("GAP").contains("budgetMax: expected x, got y");
    ObjectNode json = r.toJson(new ObjectMapper(), Map.of("provider", "mock"));
    assertThat(json.path("meta").path("provider").asText()).isEqualTo("mock");
    assertThat(json.path("casePassRate").asDouble()).isEqualTo(0.5);
    assertThat(json.path("cases")).hasSize(5);
    assertThat(json.path("cases").get(1).path("firstMismatch").asText()).contains("budgetMax");
  }
}
```

```java
package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.ai.IntentLlm;
import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.common.domain.SearchTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentEvaluatorTest {

  private static final String JSON = """
      {"version":1,"cases":[
        {"id":"one","tags":["t"],"query":"first","prior":null,"mustPass":true,"expectVerdict":null,
         "expect":{"searchTarget":"PROPERTIES","budgetMax":40000}},
        {"id":"two","tags":["t"],"query":"second","prior":{"case":"one"},"mustPass":false,"expectVerdict":"REFINE",
         "expect":{"searchTarget":"PROPERTIES","budgetMax":30000}},
        {"id":"boom","tags":["t"],"query":"explode","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}}]}
      """;

  @Test
  void runsEveryCase_handsThePriorsExpectedIntentToFollowUps_andRecordsErrors() {
    List<SearchIntent> priors = new ArrayList<>();
    IntentEvaluator.Extractor extractor = (query, prior) -> {
      priors.add(prior);
      if (query.equals("explode")) {
        throw new IllegalStateException("provider down");
      }
      SearchIntent intent = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES)
          .budgetMax(query.equals("first") ? 40000 : 30000).build();
      return new IntentArbiter.Decision(intent, prior == null ? NewQueryDetector.Verdict.NEW : NewQueryDetector.Verdict.REFINE, IntentLlm.Mode.NONE, false);
    };
    List<String> seen = new ArrayList<>();

    EvalReport report = IntentEvaluator.run(GoldenSet.parse(JSON), extractor, ref -> ref.name(), r -> seen.add(r.golden().id()));

    assertThat(seen).containsExactly("one", "two", "boom");
    assertThat(priors.get(0)).isNull();
    assertThat(priors.get(1).budgetMax()).isEqualTo(40000);
    assertThat(report.results().get(0).passed()).isTrue();
    assertThat(report.results().get(1).passed()).isTrue();
    assertThat(report.results().get(2).passed()).isFalse();
    assertThat(report.results().get(2).error()).contains("IllegalStateException").contains("provider down");
    assertThat(report.casePassRate()).isEqualTo(2.0 / 3);
  }
}
```

- [ ] **Step 2: Run to verify failure** — `./mvnw -q test -Dtest=EvalReportTest,IntentEvaluatorTest` → compilation failure.

- [ ] **Step 3: Implement**

```java
package com.flatmaite.eval;

import com.flatmaite.eval.IntentComparator.SlotResult;
import com.flatmaite.search.NewQueryDetector;
import java.util.List;

/** One golden case's outcome. {@code error} is set when the extractor threw; then no slots exist. */
public record CaseResult(GoldenCase golden, List<SlotResult> slots, NewQueryDetector.Verdict verdict, String error, long millis) {

  public boolean verdictMatches() {
    return golden.expectVerdict() == null || golden.expectVerdict() == verdict;
  }

  public boolean passed() {
    return error == null && slots.stream().allMatch(SlotResult::match) && verdictMatches();
  }

  public boolean knownGap() {
    return golden.knownGap();
  }

  /** The one line a reader needs: the first failing slot, the verdict miss, or the error. */
  public String firstMismatch() {
    if (error != null) {
      return "error: " + error;
    }
    for (SlotResult s : slots) {
      if (!s.match()) {
        return s.slot() + ": expected " + s.expected() + ", got " + s.actual();
      }
    }
    if (!verdictMatches()) {
      return "verdict: expected " + golden.expectVerdict() + ", got " + verdict;
    }
    return "";
  }
}
```

```java
package com.flatmaite.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flatmaite.eval.IntentComparator.SlotResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates over a run. Known-gap cases are reported but never counted in the gated figures. */
public final class EvalReport {

  private final List<CaseResult> results;

  public EvalReport(List<CaseResult> results) {
    this.results = List.copyOf(results);
  }

  public List<CaseResult> results() {
    return results;
  }

  public List<CaseResult> gated() {
    return results.stream().filter(r -> !r.knownGap()).toList();
  }

  public List<CaseResult> knownGaps() {
    return results.stream().filter(CaseResult::knownGap).toList();
  }

  public double casePassRate() {
    List<CaseResult> g = gated();
    return g.isEmpty() ? 1.0 : (double) g.stream().filter(CaseResult::passed).count() / g.size();
  }

  /** Per slot, over gated cases where the slot is present on either side (both "null"/"" = absent). */
  public Map<String, Double> slotAccuracy() {
    Map<String, Double> out = new LinkedHashMap<>();
    for (String slot : IntentComparator.SLOTS) {
      int present = 0;
      int matched = 0;
      for (CaseResult r : gated()) {
        for (SlotResult s : r.slots()) {
          if (!s.slot().equals(slot) || (absent(s.expected()) && absent(s.actual()))) {
            continue;
          }
          present++;
          if (s.match()) {
            matched++;
          }
        }
      }
      if (present > 0) {
        out.put(slot, (double) matched / present);
      }
    }
    return out;
  }

  private static boolean absent(String rendered) {
    return rendered == null || rendered.isEmpty() || rendered.equals("null");
  }

  public Map<String, Double> tagPassRate() {
    Map<String, int[]> counts = new TreeMap<>();
    for (CaseResult r : gated()) {
      for (String tag : r.golden().tags()) {
        int[] c = counts.computeIfAbsent(tag, t -> new int[2]);
        c[0]++;
        if (r.passed()) {
          c[1]++;
        }
      }
    }
    Map<String, Double> out = new LinkedHashMap<>();
    counts.forEach((tag, c) -> out.put(tag, (double) c[1] / c[0]));
    return out;
  }

  /** Over gated cases with an expected verdict; null when there are none. */
  public Double verdictAccuracy() {
    List<CaseResult> withVerdict = gated().stream().filter(r -> r.golden().expectVerdict() != null).toList();
    if (withVerdict.isEmpty()) {
      return null;
    }
    return (double) withVerdict.stream().filter(CaseResult::verdictMatches).count() / withVerdict.size();
  }

  public List<CaseResult> failures() {
    return gated().stream().filter(r -> !r.passed()).toList();
  }

  public List<String> mustPassFailures() {
    return failures().stream().filter(r -> r.golden().mustPass()).map(r -> r.golden().id()).toList();
  }

  /** Header + one line per case: STATUS  id  [tags]  first mismatch. */
  public String renderTable() {
    List<String> lines = new ArrayList<>();
    lines.add(String.format("%-4s  %-40s  %-28s  %s", "", "case", "tags", "first mismatch"));
    for (CaseResult r : results) {
      String status = r.knownGap() ? "GAP" : r.passed() ? "PASS" : "FAIL";
      lines.add(String.format("%-4s  %-40s  %-28s  %s", status, r.golden().id(), String.join(",", r.golden().tags()), r.firstMismatch()));
    }
    return String.join("\n", lines);
  }

  public ObjectNode toJson(ObjectMapper mapper, Map<String, Object> meta) {
    ObjectNode root = mapper.createObjectNode();
    root.set("meta", mapper.valueToTree(meta));
    root.put("cases_total", results.size());
    root.put("cases_gated", gated().size());
    root.put("casePassRate", casePassRate());
    root.set("slotAccuracy", mapper.valueToTree(slotAccuracy()));
    root.set("tagPassRate", mapper.valueToTree(tagPassRate()));
    Double v = verdictAccuracy();
    if (v == null) {
      root.putNull("verdictAccuracy");
    } else {
      root.put("verdictAccuracy", v);
    }
    root.set("mustPassFailures", mapper.valueToTree(mustPassFailures()));
    root.set("thresholdViolations", mapper.valueToTree(EvalThresholds.violations(this)));
    ArrayNode cases = root.putArray("cases");
    for (CaseResult r : results) {
      ObjectNode c = cases.addObject();
      c.put("id", r.golden().id());
      c.set("tags", mapper.valueToTree(r.golden().tags()));
      c.put("query", r.golden().query());
      c.put("passed", r.passed());
      c.put("knownGap", r.knownGap());
      c.put("verdict", r.verdict() == null ? null : r.verdict().name());
      c.put("firstMismatch", r.firstMismatch());
      c.put("millis", r.millis());
      ArrayNode slots = c.putArray("mismatches");
      for (SlotResult s : r.slots()) {
        if (!s.match()) {
          slots.addObject().put("slot", s.slot()).put("expected", s.expected()).put("actual", s.actual());
        }
      }
    }
    return root;
  }
}
```

```java
package com.flatmaite.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The offline gate's bar. The live runner reports these, it never enforces them. */
public final class EvalThresholds {

  public static final double MIN_CASE_PASS_RATE = 0.85;
  public static final double MIN_SLOT_ACCURACY = 0.90;
  public static final List<String> GATED_SLOTS = List.of("locations", "budgetMax", "roomType");

  private EvalThresholds() {}

  public static List<String> violations(EvalReport report) {
    List<String> out = new ArrayList<>();
    List<String> mustPass = report.mustPassFailures();
    if (!mustPass.isEmpty()) {
      out.add("must-pass cases failed: " + String.join(", ", mustPass));
    }
    if (report.casePassRate() < MIN_CASE_PASS_RATE) {
      out.add(String.format("case pass rate %.3f < %.2f", report.casePassRate(), MIN_CASE_PASS_RATE));
    }
    Map<String, Double> acc = report.slotAccuracy();
    for (String slot : GATED_SLOTS) {
      Double a = acc.get(slot);
      if (a != null && a < MIN_SLOT_ACCURACY) {
        out.add(String.format("slot %s accuracy %.3f < %.2f", slot, a, MIN_SLOT_ACCURACY));
      }
    }
    return out;
  }
}
```

```java
package com.flatmaite.eval;

import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runs a golden set through an extractor. Provider-agnostic: the extractor owns pacing and resolution. */
public final class IntentEvaluator {

  @FunctionalInterface
  public interface Extractor {
    IntentArbiter.Decision extract(String query, SearchIntent prior) throws Exception;
  }

  private IntentEvaluator() {}

  public static EvalReport run(GoldenSet set, Extractor extractor, Function<LocationRef, String> nameOf, Consumer<CaseResult> onCase) {
    List<CaseResult> results = new ArrayList<>();
    for (GoldenCase c : set.cases()) {
      long start = System.currentTimeMillis();
      CaseResult result;
      try {
        IntentArbiter.Decision d = extractor.extract(c.query(), set.priorOf(c));
        List<IntentComparator.SlotResult> slots =
            c.scoreIntent() ? IntentComparator.compare(c.expected(), d.intent(), nameOf) : List.of();
        result = new CaseResult(c, slots, d.verdict(), null, System.currentTimeMillis() - start);
      } catch (Exception e) {
        result = new CaseResult(c, List.of(), null, e.getClass().getSimpleName() + ": " + e.getMessage(), System.currentTimeMillis() - start);
      }
      results.add(result);
      onCase.accept(result);
    }
    return new EvalReport(results);
  }
}
```

- [ ] **Step 4: Run the tests** — `./mvnw -q test -Dtest=EvalReportTest,IntentEvaluatorTest` → 6 + 1 pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/eval backend/src/test/java/com/flatmaite/eval
git commit -m "$(cat <<'EOF'
Score golden cases per slot and per tag, with an offline threshold gate

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: The golden set and the offline gate

**Files:**
- Create: `backend/src/main/resources/eval/intent-golden.json`
- Modify: `backend/src/main/java/com/flatmaite/search/IntentLocalities.java` (visibility only: `public final class`, `public static SearchIntent resolve(...)`)
- Test: `backend/src/test/java/com/flatmaite/eval/IntentGoldenTest.java`; extend `GoldenSetTest` with two cases on the real file

**Interfaces:**
- Consumes: Tasks 1–4; `KeywordIntentParser(LocalityResolver)`, `MockLlms.MockIntentLlm(KeywordIntentParser)`, `NewQueryDetector(LocalityResolver)`, `RefinementHeuristics.apply(prior, query)`, `LocalityResolver(LocalityRepository)` + `reload()` + `nameOf(UUID)`.

- [ ] **Step 1: Make `IntentLocalities` public** (class and `resolve`; nothing else changes).

- [ ] **Step 2: Author the golden set**

Write `intent-golden.json` from the table below. Conventions: every case has `"searchTarget"` explicitly (`PROPERTIES` unless the row says `FLATMATES`); place names are the canonical names from `SeedLocalities` (e.g. `Andheri East`, `BKC`, `Lower Parel`, `Ram Mandir`); a `C(place, n)` cell means `"commuteTo": {"place": "<place>", "maxMinutes": n}`; `BHK n` means `{"min": n, "max": n}`; lifestyle values are the strings the parser emits (`NO_SMOKERS`, `PET_FRIENDLY`, `VEGETARIAN`); `MP` = `"mustPass": true`; `prior=<id>` sets `"prior": {"case": "<id>"}`; `V=` sets `expectVerdict`; `verdict-only` sets `"scoreIntent": false, "expect": {}`. Cases appear in the file in table order (priors reference earlier rows). Any slot not listed in the row is omitted (= expected null).

| id | tags | query | expect |
|---|---|---|---|
| basics-2bhk-powai | basics | 2bhk in powai under 40k | L[Powai]; BHK 2; budgetMax 40000 — MP |
| basics-private-goregaon | basics | private room in goregaon | L[Goregaon]; roomType PRIVATE — MP |
| basics-flatmate-andheri | basics | looking for a flatmate in andheri | FLATMATES; L[Andheri East, Andheri West] — MP |
| basics-1rk-bandra | basics | 1rk in bandra | L[Bandra]; roomType ENTIRE |
| basics-studio-malad | basics | studio apartment in malad under 20000 | L[Malad]; roomType ENTIRE; budgetMax 20000 |
| basics-furnished-1bhk | basics | fully furnished 1bhk in kandivali | L[Kandivali]; BHK 1; furnished FULLY_FURNISHED |
| basics-semi-chembur | basics | semi furnished room in chembur | L[Chembur]; furnished SEMI_FURNISHED |
| basics-double-sharing-bkc | basics, commute | double sharing room near bkc 15k | C(BKC, 30); roomType SHARED; budgetMax 15000 — MP |
| basics-pg-girls-thane | basics | pg in thane for girls | L[Thane]; roomType SHARED; genderPreference FEMALE_ONLY |
| basics-single-pg-vashi | basics | single room pg in vashi | L[Vashi]; roomType PRIVATE |
| budget-under-k | budget | room in kurla under 12k | L[Kurla]; budgetMax 12000 — MP |
| budget-floor-more-than | budget | flat in andheri more than 30000 | L[Andheri East, Andheri West]; budgetMin 30000 — MP |
| budget-range-between | budget, commute | between 20k and 30k near dadar | C(Dadar, 30); budgetMin 20000; budgetMax 30000 — MP |
| budget-range-from-to | budget | from 20k to 30k in ghatkopar | L[Ghatkopar]; budgetMin 20000; budgetMax 30000 — MP |
| budget-spelled-lakh | budget | one and a half lakh flat in worli | L[Worli]; budgetMax 150000 |
| budget-lakh-numeric | budget | 2 lakh budget apartment in juhu | L[Juhu]; budgetMax 200000 |
| budget-deposit-vs-rent | budget | 2 lakh deposit, 30k rent in powai | L[Powai]; maxDeposit 200000; budgetMax 30000 — MP |
| budget-after-commute | budget, commute | room within 20 min of bkc, 25k | C(BKC, 20); budgetMax 25000 — MP |
| budget-after-place | budget, commute | 15 min from powai for 25000 | C(Powai, 15); budgetMax 25000 — MP |
| budget-pincode-not-budget | budget, locality | 1bhk in andheri east 400069 | L[Andheri East]; BHK 1 — MP |
| budget-minimum | budget | minimum 25k private room in bandra | L[Bandra]; roomType PRIVATE; budgetMin 25000 — MP |
| budget-tak-hinglish | budget, hinglish | andheri me 1bhk 30k tak | L[Andheri East, Andheri West]; BHK 1; budgetMax 30000 |
| budget-rs-format | budget | rs. 18,000 room in sion | L[Sion]; budgetMax 18000 |
| budget-not-above | budget | not above 30000 in mulund | L[Mulund]; budgetMax 30000 |
| loc-bkc-full | locality | flat in bandra kurla complex | L[BKC] — MP |
| loc-bkc-short | locality | 1bhk in bkc | L[BKC]; BHK 1 — MP |
| loc-fuzzy-powaii | locality | flats in powaii | L[Powai] |
| loc-mansion-not-sion | locality | 2bhk in a mansion in worli | L[Worli]; BHK 2 — MP |
| loc-andheri-ambiguous | locality | room in andheri | L[Andheri East, Andheri West] — MP |
| loc-andheri-east | locality | room in andheri east | L[Andheri East] — MP |
| loc-lower-parel | locality | 2bhk in lower parel | L[Lower Parel]; BHK 2 — MP |
| loc-parel | locality | 2bhk in parel | L[Parel]; BHK 2 — MP |
| loc-two-places | locality | private room in powai or vikhroli | L[Powai, Vikhroli]; roomType PRIVATE |
| loc-alias-hiranandani | locality | flat in hiranandani | L[Powai] |
| loc-alias-navi-mumbai | locality | pg in navi mumbai | L[Vashi]; roomType SHARED |
| loc-unknown-wakad | locality | 1bhk in wakad | BHK 1; unresolvedLocations [wakad] |
| loc-film-city-commute | locality, commute | room near film city | C(Goregaon, 30) |
| loc-ram-mandir | locality | single sharing in ram mandir | L[Ram Mandir]; roomType PRIVATE |
| excl-not-in | exclusion | 2bhk not in powai | BHK 2; X[Powai] — MP |
| excl-anywhere-but | exclusion | private room anywhere but goregaon | roomType PRIVATE; X[Goregaon] — MP |
| excl-nahi-hinglish | exclusion, hinglish | andheri nahi, malad chalega | X[Andheri East, Andheri West]; L[Malad] |
| excl-except | exclusion | any locality except bandra under 30k | X[Bandra]; budgetMax 30000 |
| excl-no-smokers-is-lifestyle | exclusion, lifestyle | no smokers in andheri | L[Andheri East, Andheri West]; lifestyle.smoking NO_SMOKERS — MP |
| excl-mixed | exclusion | room in powai or vikhroli but not kanjurmarg | L[Powai, Vikhroli]; X[Kanjurmarg] |
| commute-near | commute | room near bkc | C(BKC, 30) — MP |
| commute-office-in | commute | office in lower parel, need a 1bhk | C(Lower Parel, 30); BHK 1 |
| commute-within-mins | commute | within 30 mins of dadar | C(Dadar, 30) — MP |
| commute-work-at | commute | i work at powai, looking for a private room | C(Powai, 30); roomType PRIVATE |
| commute-close-to | commute, budget | close to marol under 20k | C(Marol, 30); budgetMax 20000 |
| commute-reversed-from | commute | 10 min from kurla station | C(Kurla, 10) — MP |
| commute-home-plus-office | commute, locality | room in andheri, office in bkc | L[Andheri East, Andheri West]; C(BKC, 30) |
| commute-station-is-home | commute, locality | 2 min from the station in vile parle | L[Vile Parle] |
| life-nonsmoker-flatmate | lifestyle | non-smoker flatmate in powai | FLATMATES; L[Powai]; lifestyle.smoking NO_SMOKERS — MP |
| life-veg | lifestyle | veg only flat in matunga | L[Matunga]; lifestyle.diet VEGETARIAN |
| life-pets | lifestyle | pet friendly 1bhk in bandra | L[Bandra]; BHK 1; lifestyle.pets PET_FRIENDLY |
| life-female-flatmate | lifestyle | female flatmate wanted in khar | FLATMATES; L[Khar]; genderPreference FEMALE_ONLY |
| life-couples | lifestyle | couple friendly flat in goregaon | L[Goregaon]; couplesOk true |
| life-amenities | lifestyle | 2bhk with gym and parking in thane | L[Thane]; BHK 2; amenities [gym, parking] |
| life-verified | lifestyle | only verified listings in powai | L[Powai]; verifiedOnly true — MP |
| life-quiet-flatmates | lifestyle | quiet flatmates, no parties, in chembur | FLATMATES; L[Chembur]; lifestyle.quiet true |
| hi-single-sharing | hinglish | Single sharing room chahiye powai me budget 40k hai | L[Powai]; roomType PRIVATE; budgetMax 40000 — MP |
| hi-ke-andar | hinglish, budget | 2bhk chahiye malad west mein 25k ke andar | L[Malad]; BHK 2; budgetMax 25000 |
| hi-flatmate-ladki | hinglish, lifestyle | flatmate chahiye andheri east me, ladki | FLATMATES; L[Andheri East]; genderPreference FEMALE_ONLY |
| hi-paying-guest | hinglish | paying guest chahiye borivali me 10k | L[Borivali]; roomType SHARED; budgetMax 10000 |
| ref-make-it | refinement | make it 30k | prior=basics-2bhk-powai; V=REFINE; L[Powai]; BHK 2; budgetMax 30000 — MP |
| ref-same-but-in | refinement | same but in andheri | prior=basics-2bhk-powai; V=REFINE; L[Andheri East, Andheri West]; BHK 2; budgetMax 40000 — MP |
| ref-not-in-prior | refinement, exclusion | not in powai | prior=basics-2bhk-powai; V=REFINE; X[Powai]; BHK 2; budgetMax 40000 — MP |
| ref-forget-that | refinement | forget that, single sharing room in goregaon 20k | prior=basics-2bhk-powai; V=NEW; L[Goregaon]; roomType PRIVATE; budgetMax 20000 — MP |
| ref-fresh-three-anchors | refinement | 2bhk in andheri for 60k | prior=basics-private-goregaon; V=NEW; L[Andheri East, Andheri West]; BHK 2; budgetMax 60000 — MP |
| ref-only-verified | refinement | only verified listings | prior=basics-2bhk-powai; V=REFINE; L[Powai]; BHK 2; budgetMax 40000; verifiedOnly true — MP |
| ref-add-furnished | refinement | fully furnished | prior=basics-private-goregaon; V=REFINE; L[Goregaon]; roomType PRIVATE; furnished FULLY_FURNISHED |
| ref-no-anchor-lifestyle | refinement, lifestyle | no smokers please | prior=basics-2bhk-powai; V=REFINE; L[Powai]; BHK 2; budgetMax 40000; lifestyle.smoking NO_SMOKERS |
| ref-actually-two-anchors | refinement | actually make it andheri and 30k | prior=basics-2bhk-powai; V=REFINE; L[Andheri East, Andheri West]; BHK 2; budgetMax 30000 — MP |
| ref-ambiguous-one-anchor | refinement, arbiter | flats in goregaon | prior=basics-2bhk-powai; V=AMBIGUOUS; verdict-only — MP |
| ref-closer-to-work | refinement, arbiter | closer to work | prior=basics-double-sharing-bkc; V=REFINE; verdict-only — MP |
| arb-three-anchors-with-cue | arbiter | only single sharing room in powai 40k | prior=basics-2bhk-powai; V=NEW; verdict-only — MP |
| arb-fuzzy-not-an-anchor | arbiter | flats in powaii | prior=basics-2bhk-powai; V=REFINE; verdict-only — MP |
| arb-start-over | arbiter | start over: 1bhk near bkc | prior=basics-2bhk-powai; V=NEW; verdict-only — MP |

Two rows rendered in full, as the file must look:

```json
{
  "id": "budget-after-commute",
  "tags": ["budget", "commute"],
  "query": "room within 20 min of bkc, 25k",
  "prior": null,
  "mustPass": true,
  "expectVerdict": null,
  "expect": {
    "searchTarget": "PROPERTIES",
    "commuteTo": {"place": "BKC", "maxMinutes": 20},
    "budgetMax": 25000
  }
},
{
  "id": "ref-not-in-prior",
  "tags": ["refinement", "exclusion"],
  "query": "not in powai",
  "prior": {"case": "basics-2bhk-powai"},
  "mustPass": true,
  "expectVerdict": "REFINE",
  "expect": {
    "searchTarget": "PROPERTIES",
    "excludeLocations": ["Powai"],
    "bhk": {"min": 2, "max": 2},
    "budgetMax": 40000
  }
}
```

- [ ] **Step 3: Extend `GoldenSetTest` for the real file**

```java
  @Test
  void theRealGoldenSet_loads_andIsBigEnough() {
    GoldenSet set = GoldenSet.load();
    assertThat(set.version()).isEqualTo(1);
    assertThat(set.cases()).hasSizeGreaterThanOrEqualTo(70);
    assertThat(set.cases().stream().filter(GoldenCase::mustPass).count()).isGreaterThanOrEqualTo(30);
  }

  @Test
  void theRealGoldenSet_everyCaseHasATag_andStatesSearchTargetWhenScoringIntent() {
    for (GoldenCase c : GoldenSet.load().cases()) {
      assertThat(c.tags()).as(c.id()).isNotEmpty();
      if (c.scoreIntent()) {
        assertThat(c.expected().searchTarget()).as(c.id() + " must state searchTarget").isNotNull();
      }
    }
  }
```

- [ ] **Step 4: Write the gate**

```java
package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.IntentLlm;
import com.flatmaite.ai.MockLlms;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.IntentLocalities;
import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.LocalityResolver;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.RefinementHeuristics;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.seed.SeedLocalities;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The offline gate: the keyword parser (the mock LLM) and the arbiter against the golden set, over
 * the seed gazetteer, with no Spring and no database. Mirrors SearchPipeline.extractIntent's
 * mock path: heuristics first, then the parser, then locality resolution.
 */
class IntentGoldenTest {

  private static LocalityResolver resolver;
  private static IntentEvaluator.Extractor extractor;
  private static Function<LocationRef, String> nameOf;

  @BeforeAll
  static void wire() {
    LocalityRepository repo = mock(LocalityRepository.class);
    when(repo.findAll()).thenReturn(SeedLocalities.entities());
    resolver = new LocalityResolver(repo);
    resolver.reload();
    KeywordIntentParser parser = new KeywordIntentParser(resolver);
    IntentLlm llm = new MockLlms.MockIntentLlm(parser);
    IntentArbiter arbiter = new IntentArbiter(new NewQueryDetector(resolver));
    nameOf = ref -> ref.localityId() != null ? resolver.nameOf(ref.localityId()) : ref.name();
    extractor =
        (query, prior) -> {
          SearchIntent p = prior == null ? null : IntentLocalities.resolve(prior, resolver);
          return arbiter.decide(
              query,
              p,
              (q, pp) -> {
                SearchIntent heuristic = RefinementHeuristics.apply(pp, q);
                SearchIntent raw = heuristic != null ? heuristic : llm.extract(q, pp);
                return new IntentLlm.Extraction(IntentLocalities.resolve(raw, resolver), IntentLlm.Mode.NONE);
              });
        };
  }

  @Test
  void keywordParser_meetsTheBar() {
    EvalReport report = IntentEvaluator.run(GoldenSet.load(), extractor, nameOf, r -> {});
    List<String> violations = EvalThresholds.violations(report);
    assertThat(violations)
        .withFailMessage(
            "Intent eval thresholds violated:\n  %s\n\n%s\n\ncase pass rate %.3f, slot accuracy %s",
            String.join("\n  ", violations), report.renderTable(), report.casePassRate(), report.slotAccuracy())
        .isEmpty();
  }

  @Test
  void knownGaps_areListed_notHidden() {
    EvalReport report = IntentEvaluator.run(GoldenSet.load(), extractor, nameOf, r -> {});
    System.out.println("known gaps (" + report.knownGaps().size() + "):");
    report.knownGaps().forEach(r -> System.out.println("  " + r.golden().id() + " — " + r.firstMismatch()));
    assertThat(report.knownGaps()).allMatch(r -> !r.golden().mustPass());
  }
}
```

- [ ] **Step 5: Run the gate and triage honestly**

Run: `./mvnw -q test -Dtest=IntentGoldenTest,GoldenSetTest`

For every failing case in the printed table apply, in this order:
1. **Must-pass failure** → the golden expectation is wrong (these rows mirror existing unit tests). Re-read the row against the corresponding unit test, correct the golden value, and record the correction in your report. If the parser genuinely fails a must-pass row, stop and report BLOCKED with the diff — the parser is frozen in WS3.
2. **Non-must-pass failure that is a golden mistake** (the human truth is genuinely different from what the row says — e.g. a place the resolver canonicalises differently): fix the golden value and record why.
3. **Non-must-pass failure that is a parser limitation** (amenities, couplesOk, an unhandled Hinglish word, a missed lifestyle cue…): add `"known-gap"` to the case's tags. Do not touch the parser. List every known-gap in your report with its first mismatch.

Then re-run. The gate must pass with the thresholds as constants — never lower a threshold. Expected shape: ≥ 30 must-pass rows all passing; a handful of known gaps (amenities, couples, some Hinglish/lifestyle rows are likely).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/eval/intent-golden.json backend/src/main/java/com/flatmaite/search/IntentLocalities.java backend/src/test/java/com/flatmaite/eval/IntentGoldenTest.java backend/src/test/java/com/flatmaite/eval/GoldenSetTest.java
git commit -m "$(cat <<'EOF'
Add the intent golden set and gate the keyword parser on it in every build

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `EvalRunner` — the `eval` profile for live providers

**Files:**
- Create: `backend/src/main/java/com/flatmaite/eval/EvalRunner.java`, `backend/src/main/resources/application-eval.yml`
- Test: manual smoke run (below); no new unit test (the core is covered by Tasks 3–5)

**Interfaces:**
- Consumes: `SearchPipeline.extractIntent(query, prior, userId, anonKey)`, `IntentArbiter`, `LocalityResolver`, `IntentLlm` (`healthCheck()`, `providerName()`, `model()`, `promptOverheadTokens()`), `AiProviderConfig.useMock(props, provider, openaiKey, geminiKey)`, `FlatmaiteProperties`, `GoldenSet.filter`, `IntentEvaluator.run`, `EvalReport.toJson`.

- [ ] **Step 1: Profile file**

`backend/src/main/resources/application-eval.yml`:

```yaml
# Eval profile: run EvalRunner against the configured AI provider and exit (no web server).
spring:
  main:
    web-application-type: none
```

- [ ] **Step 2: The runner**

```java
package com.flatmaite.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.ai.AiProviderConfig;
import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.IntentLocalities;
import com.flatmaite.search.LocalityResolver;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.search.SearchPipeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the golden set through the real wiring — provider, database gazetteer, arbiter — and writes
 * a report. Reports the offline thresholds but never enforces them: a live run informs, the build
 * gate is the keyword parser. Activate with the "eval" profile; refuses the mock provider unless
 * EVAL_ALLOW_MOCK=true (a smoke test of the runner itself).
 */
@Component
@Profile("eval")
@Slf4j
public class EvalRunner implements ApplicationRunner {

  private final SearchPipeline pipeline;
  private final IntentArbiter arbiter;
  private final LocalityResolver resolver;
  private final IntentLlm intentLlm;
  private final ObjectMapper mapper;
  private final FlatmaiteProperties props;
  private final String provider;
  private final String openaiKey;
  private final String geminiKey;
  private final long paceMs;
  private final Set<String> tags;
  private final int limit;
  private final boolean allowMock;

  public EvalRunner(
      SearchPipeline pipeline,
      IntentArbiter arbiter,
      LocalityResolver resolver,
      IntentLlm intentLlm,
      ObjectMapper mapper,
      FlatmaiteProperties props,
      @Value("${spring.ai.model.chat:openai}") String provider,
      @Value("${spring.ai.openai.api-key}") String openaiKey,
      @Value("${spring.ai.google.genai.api-key:}") String geminiKey,
      @Value("${EVAL_PACE_MS:4500}") long paceMs,
      @Value("${EVAL_TAGS:}") String tags,
      @Value("${EVAL_LIMIT:0}") int limit,
      @Value("${EVAL_ALLOW_MOCK:false}") boolean allowMock) {
    this.pipeline = pipeline;
    this.arbiter = arbiter;
    this.resolver = resolver;
    this.intentLlm = intentLlm;
    this.mapper = mapper;
    this.props = props;
    this.provider = provider;
    this.openaiKey = openaiKey;
    this.geminiKey = geminiKey;
    this.paceMs = paceMs;
    this.tags = tags.isBlank() ? Set.of() : Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    this.limit = limit;
    this.allowMock = allowMock;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    boolean mock = AiProviderConfig.useMock(props, provider, openaiKey, geminiKey);
    if (mock && !allowMock) {
      throw new IllegalStateException(
          "The eval profile needs a real provider: set FM_AI_PROVIDER=google-genai GEMINI_API_KEY=… (or OPENAI_API_KEY), or EVAL_ALLOW_MOCK=true to smoke-test the runner");
    }
    intentLlm.healthCheck();
    GoldenSet set = GoldenSet.load().filter(tags, limit);
    log.info("Intent eval: provider={} model={} cases={} pace={}ms", intentLlm.providerName(), intentLlm.model(), set.cases().size(), paceMs);

    Function<LocationRef, String> nameOf = ref -> ref.localityId() != null ? resolver.nameOf(ref.localityId()) : ref.name();
    long[] calls = {0};
    IntentEvaluator.Extractor extractor =
        (query, prior) -> {
          SearchIntent p = prior == null ? null : IntentLocalities.resolve(prior, resolver);
          return arbiter.decide(
              query,
              p,
              (q, pp) -> {
                pace(calls[0]++ > 0);
                return pipeline.extractIntent(q, pp, null, "eval");
              });
        };

    long started = System.currentTimeMillis();
    EvalReport report =
        IntentEvaluator.run(set, extractor, nameOf, r -> log.info("{} {} {}", r.passed() ? "PASS" : r.knownGap() ? "GAP " : "FAIL", r.golden().id(), r.firstMismatch()));

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("provider", intentLlm.providerName());
    meta.put("model", intentLlm.model());
    meta.put("mock", mock);
    meta.put("cases", set.cases().size());
    meta.put("providerCalls", calls[0]);
    meta.put("elapsedMs", System.currentTimeMillis() - started);
    meta.put("paceMs", paceMs);
    meta.put("promptOverheadTokens", intentLlm.promptOverheadTokens());
    meta.put("thresholdsEnforced", false);

    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path out = Path.of("target", "eval", intentLlm.providerName() + "-" + intentLlm.model() + "-" + stamp + ".json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.toJson(mapper, meta)));

    System.out.println();
    System.out.println(report.renderTable());
    System.out.printf("%ncase pass rate %.3f over %d gated cases (%d known gaps)%n", report.casePassRate(), report.gated().size(), report.knownGaps().size());
    System.out.println("slot accuracy " + report.slotAccuracy());
    System.out.println("tag pass rate " + report.tagPassRate());
    System.out.println("verdict accuracy " + report.verdictAccuracy());
    System.out.println("threshold violations (informational): " + EvalThresholds.violations(report));
    System.out.println("report: " + out.toAbsolutePath());
  }

  private void pace(boolean notFirst) throws InterruptedException {
    if (notFirst && paceMs > 0) {
      Thread.sleep(paceMs);
    }
  }
}
```

(`pace` is called from a lambda that cannot throw checked exceptions — wrap `InterruptedException` in an `IllegalStateException` after restoring the interrupt flag, or make `pace` swallow-and-restore; keep the behaviour: sleep `paceMs` before every provider call except the first.)

- [ ] **Step 3: Compile and smoke-run against the mock provider**

Run: `./mvnw -q compile`.
Then, from the repo root, `docker compose up -d` (the local Postgres on :5433). If the local database has no localities (`docker exec` a `psql -c "select count(*) from locality"` — or simply run it), seed it once: `cd backend && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=seed`. Then:

`cd backend && EVAL_ALLOW_MOCK=true EVAL_PACE_MS=0 ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=eval`

Expected: the table prints, a JSON lands in `target/eval/mock-keyword-…json`, the process exits 0, and the pass rate equals the offline gate's (same parser, same gazetteer via the database). Paste the summary lines into your report. If the DB cannot be reached, report that with the error and mark the smoke as not run — do not skip silently.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/flatmaite/eval/EvalRunner.java backend/src/main/resources/application-eval.yml
git commit -m "$(cat <<'EOF'
Add the eval profile: run the golden set through the live provider and write a report

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Documentation and full verification

**Files:**
- Modify: `README.md` (new "Intent eval" subsection under `## Tests & checks`; env rows)
- Create: `docs/eval/README.md`

- [ ] **Step 1: README**

Under `## Tests & checks` add:

```markdown
### Intent eval

Every `./mvnw verify` runs `IntentGoldenTest`: the keyword parser and the new-vs-refine arbiter
against `backend/src/main/resources/eval/intent-golden.json` (≈ 75 real-shaped queries, including
multi-turn follow-ups). The build fails if any must-pass case fails, the case pass rate drops below
0.85, or `locations` / `budgetMax` / `roomType` slot accuracy drops below 0.90. Cases tagged
`known-gap` are reported but not counted.

Run the same set through the real model (never gates; paced for Gemini's free tier):

```bash
cd backend && FM_AI_PROVIDER=google-genai GEMINI_API_KEY=… ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval
```

The report prints as a table and lands in `backend/target/eval/<provider>-<model>-<timestamp>.json`.
Knobs: `EVAL_PACE_MS` (default 4500), `EVAL_TAGS=budget,commute`, `EVAL_LIMIT=10`,
`EVAL_ALLOW_MOCK=true` (smoke-test the runner with the keyword parser). Adding cases:
`docs/eval/README.md`.
```

In the environment-variable table add, after `SEARCH_NEARBY_RADIUS_MINUTES`:

```markdown
| `EVAL_PACE_MS` | `4500` | Eval profile only — delay between provider calls |
| `EVAL_TAGS` / `EVAL_LIMIT` | all / `0` | Eval profile only — run a subset of the golden set |
| `EVAL_ALLOW_MOCK` | `false` | Eval profile only — allow the mock provider (runner smoke test) |
```

- [ ] **Step 2: `docs/eval/README.md`**

```markdown
# Intent eval — adding cases

`backend/src/main/resources/eval/intent-golden.json` is the answer key for intent extraction.

- One case = one message and the `SearchIntent` it should produce. **A slot you leave out of `expect`
  is expected to be null/empty** — write down everything the message implies, and nothing it does not.
- Always state `searchTarget` (`PROPERTIES`, `FLATMATES`, `BOTH`).
- Places are canonical names from `SeedLocalities` (`Andheri East`, `BKC`, `Lower Parel`, …). An
  ambiguous alias such as "andheri" expects both `Andheri East` and `Andheri West`.
- `commuteTo` is `{"place": "BKC", "maxMinutes": 30}`; the default radius is 30 when the message gives none.
- Follow-ups: `"prior": {"case": "<earlier id>"}` — the prior is that case's *expected* intent — and
  `"expectVerdict": "NEW" | "REFINE" | "AMBIGUOUS"`. Use `"scoreIntent": false` to grade only the verdict.
- `"mustPass": true` for rows that mirror a unit test or a shipped fix — these fail the build individually.
- `"known-gap"` in `tags` marks a row the keyword parser is known to miss (the live model is still
  graded on it). Known-gap rows are excluded from the offline aggregates and can never be must-pass.
- Ids are unique, kebab-case, prefixed by their main tag. Keep priors above the rows that use them.

Run the gate alone: `cd backend && ./mvnw -q test -Dtest=IntentGoldenTest` — a failure prints the
whole table with the first mismatch per case.
```

- [ ] **Step 3: Full verification**

Run: `./mvnw verify` from `backend/` (Docker up). Expected new/changed classes: `SeedLocalitiesTest` 5, `IntentArbiterTest` 5, `AiSearchControllerTest` 1, `GoldenSetTest` 8, `IntentComparatorTest` 7, `EvalReportTest` 6, `IntentEvaluatorTest` 1, `IntentGoldenTest` 2; every pre-existing class unchanged; `BUILD SUCCESS`. Report the summary `Tests run:` line. `git status --short` must be empty after the commit.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/eval/README.md
git commit -m "$(cat <<'EOF'
Document the intent eval gate and the live eval profile

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8 (manual, after the final review): the first live run

Not a subagent task. Archit runs, from `backend/`, with the key exported in his own terminal:

```bash
FM_AI_PROVIDER=google-genai GEMINI_API_KEY=… ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval
```

The controller reads the printed table and `backend/target/eval/google-genai-*.json`, writes
`docs/eval/2026-09-15-google-genai-<model>.md` (pass rate, per-slot table, per-tag table, verdict
accuracy, `promptOverheadTokens` vs any provider-reported usage, every failing case with its first
mismatch, and which failures look like model behaviour vs golden mistakes), and commits it. Live
findings that need code changes become WS4 input unless they are plain bugs (e.g. the
`RefineResult` converter rejected by the provider), which get their own fix cycle.
