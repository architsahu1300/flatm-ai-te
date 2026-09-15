# Confidence-Gated Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop enforcing the reader's guesses as hard SQL filters — grade every slot's confidence, demote the ungrounded ones to ranking preferences, and top up a thin result page automatically with clearly-marked nearby and near-miss results.

**Architecture:** One pure grader (`IntentGrounding`) answers "can I point at the words that produced this value?" from `(intent, query)` alone, so the keyword parser and the LLM are graded by identical code. `SearchPipeline.extractIntent` mins that against the model's own self-rating (which the provider adapter writes straight into `SearchIntent.confidence`) on every path — LLM, mock, heuristic, cache. One pure `ConfidenceGate` holds the threshold and the never-soft set; `HybridRetriever.toFilters` consults it to decide which slots reach SQL, and `MatchScorer` turns the rest into a `preferences` component. When the hard-filtered page is still thin, `SearchPipeline.searchHomes` walks a deterministic rescue ladder — wider ring first, then least-confident hard slot — merging extra candidates into one hydration and scoring pass, marking them `nearMiss` and sorting them below every exact match.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI 1.1.8, Lombok records/builders, Jackson, JUnit 5 + AssertJ + Mockito, Testcontainers (`pgvector/pgvector:pg16`), Next.js 15 TypeScript mirror.

**Spec:** `docs/superpowers/specs/2026-09-15-confidence-gating-design.md`

## Global Constraints

- Java 17; Maven wrapper — every backend command from `backend/`, frontend commands from `frontend/`. Docker Desktop required for the Testcontainers classes (`LocationWideningIntegrationTest`, `SearchPipelineIntegrationTest`, `HybridRetrieverIntegrationTest`, `AuthFlowIntegrationTest`); everything else is pure. Never run two Maven builds at once.
- **No schema change, no Flyway migration, no new dependency, no new provider call.** `SearchIntent` changes are additive only (`Map<String, Double> confidence`).
- **WS1–WS3 constants untouched:** `RankFusion.K = 60`, `VECTOR_LIMIT = 100`, `FTS_LIMIT = 50`, `MAX_LEXICAL_TOKENS = 24`, `MAX_FREE_TEXT_CHARS = 600`, relevance 0.15/0.20, `MAX_WINDOW = 3`, `FUZZY_THRESHOLD = 0.55`, `CONFIDENT = 0.75`, `MIN_FUZZY_LENGTH = 5`, `DEFAULT_COMMUTE_MINUTES = 30`, `nearbyRadiusMinutes = 25`, `EvalThresholds` unchanged.
- **Extraction behaviour is frozen.** `KeywordIntentParser`, `NewQueryDetector`, `LocationMentions`, `RefinementHeuristics`, `IntentLocalities`, `IntentArbiter` keep their logic; no task edits them. `OpenAiLlms` and `MockLlms` change only where Task 6 names them (prompt text, schema, confidence merge) — never what values they extract. **WS3's `IntentGoldenTest` must stay green at 1.000 gated in every task.**
- New constants (verbatim): `ConfidenceGate.HARD_THRESHOLD = 0.75`, `ConfidenceGate.ALWAYS_HARD = Set.of("excludeLocations", "verifiedOnly")`, grounding levels `STATED = 1.0` / `WEAK = 0.75` / `INFERRED = 0.5`, `MatchScorer` preferences weight `0.15`, `flatmaite.search.min-results` default **6** (`SEARCH_MIN_RESULTS`), `flatmaite.search.rescue-radius-minutes` default **45** (`SEARCH_RESCUE_RADIUS_MINUTES`).
- Slot keys (verbatim, this order — it is also the deterministic tie-break order for the rescue ladder): `locations, excludeLocations, budgetMin, budgetMax, maxDeposit, roomType, listingTypes, furnished, bhk, moveInDate, genderPreference, couplesOk, amenities, lifestyle, commuteTo, commuteTo.maxMinutes, verifiedOnly`.
- Copy (verbatim): soft-slot note `"Some of these are preferences, not filters: %s."`; rescue note `"Only %d exact %s — added %d nearby option%s (%s)."`; preference details `"Matches your preferred %s"` / `"%s — a preference, not a requirement"`; near-miss reasons `"~%d min from %s"` and `"%s — you asked for %s"`.
- Commit messages: short imperative subject in the repo's style, ending with the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` — copy it exactly; never substitute another model name.
- Branch: `confidence-gating` (already created from `main` at `07a9ed7`; the spec is its first two commits).

---

### Task 1: `SearchIntent.confidence`

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/SearchIntent.java`
- Test: `backend/src/test/java/com/flatmaite/search/SearchIntentConfidenceTest.java`

**Interfaces:**
- Produces: `SearchIntent.confidence()` (`Map<String, Double>`, nullable, last record component before `freeText`... see Step 1 for exact placement); `public double confidenceOf(String slot)`; `public static Map<String, Double> mergeConfidence(Map<String, Double> prior, Map<String, Double> next)`; `public static final List<String> GATED_SLOTS`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing test**

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Absent confidence must read as 1.0: everything that shipped before this change was enforced as a
 * hard filter, so an old session or a provider that says nothing keeps exactly today's behaviour.
 */
class SearchIntentConfidenceTest {

  @Test
  void absentMapOrKey_readsAsFullyConfident() {
    SearchIntent none = SearchIntent.builder().budgetMax(25000).build();
    assertThat(none.confidence()).isNull();
    assertThat(none.confidenceOf("budgetMax")).isEqualTo(1.0);

    SearchIntent some =
        SearchIntent.builder().budgetMax(25000).confidence(Map.of("roomType", 0.5)).build();
    assertThat(some.confidenceOf("roomType")).isEqualTo(0.5);
    assertThat(some.confidenceOf("budgetMax")).isEqualTo(1.0);
    assertThat(some.confidenceOf("nonsense")).isEqualTo(1.0);
  }

  @Test
  void nullValueInTheMap_readsAsFullyConfident() {
    Map<String, Double> raw = new HashMap<>();
    raw.put("roomType", null);
    assertThat(SearchIntent.builder().confidence(raw).build().confidenceOf("roomType")).isEqualTo(1.0);
  }

  @Test
  void merge_prefersTheNewerValue_andKeepsCarriedSlots() {
    Map<String, Double> prior = new LinkedHashMap<>();
    prior.put("locations", 1.0);
    prior.put("roomType", 0.5);
    Map<String, Double> next = new LinkedHashMap<>();
    next.put("roomType", 1.0);
    next.put("budgetMax", 1.0);

    Map<String, Double> merged = SearchIntent.mergeConfidence(prior, next);

    assertThat(merged).containsEntry("locations", 1.0); // carried from the prior turn
    assertThat(merged).containsEntry("roomType", 1.0); // re-stated this turn, re-graded
    assertThat(merged).containsEntry("budgetMax", 1.0);
  }

  @Test
  void merge_toleratesNulls() {
    assertThat(SearchIntent.mergeConfidence(null, null)).isNull();
    assertThat(SearchIntent.mergeConfidence(Map.of("a", 0.5), null)).containsEntry("a", 0.5);
    assertThat(SearchIntent.mergeConfidence(null, Map.of("b", 0.5))).containsEntry("b", 0.5);
  }

  @Test
  void gatedSlots_areTheSeventeenInSpecOrder() {
    assertThat(SearchIntent.GATED_SLOTS)
        .containsExactly(
            "locations", "excludeLocations", "budgetMin", "budgetMax", "maxDeposit", "roomType",
            "listingTypes", "furnished", "bhk", "moveInDate", "genderPreference", "couplesOk",
            "amenities", "lifestyle", "commuteTo", "commuteTo.maxMinutes", "verifiedOnly");
  }

  @Test
  void confidenceSurvivesAJsonRoundTrip_andOldJsonStillParses() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    SearchIntent intent =
        SearchIntent.builder().budgetMax(25000).confidence(Map.of("roomType", 0.5)).build();
    String json = mapper.writeValueAsString(intent);
    assertThat(json).contains("\"confidence\"");
    assertThat(mapper.readValue(json, SearchIntent.class).confidenceOf("roomType")).isEqualTo(0.5);

    // an intent stored before this change
    SearchIntent old = mapper.readValue("{\"budgetMax\":25000}", SearchIntent.class);
    assertThat(old.confidence()).isNull();
    assertThat(old.confidenceOf("roomType")).isEqualTo(1.0);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=SearchIntentConfidenceTest`
Expected: compilation failure — `confidence` / `confidenceOf` / `mergeConfidence` / `GATED_SLOTS` do not exist.

- [ ] **Step 3: Add the field and helpers**

In `SearchIntent.java`, add `Map<String, Double> confidence,` as a new record component **immediately after `verifiedOnly`** (before `freeText`), add `import java.util.LinkedHashMap; import java.util.Map;`, and add these members next to `lifestyleOrEmpty()`:

```java
  /**
   * Slots whose enforcement is confidence-gated, in the order used to break ties when the rescue
   * ladder picks which filter to drop first.
   */
  public static final List<String> GATED_SLOTS =
      List.of(
          "locations", "excludeLocations", "budgetMin", "budgetMax", "maxDeposit", "roomType",
          "listingTypes", "furnished", "bhk", "moveInDate", "genderPreference", "couplesOk",
          "amenities", "lifestyle", "commuteTo", "commuteTo.maxMinutes", "verifiedOnly");

  /**
   * How directly the user's own words support this slot. An absent map or key means 1.0: everything
   * that shipped before confidence gating was enforced as a hard filter, and an old session or a
   * silent provider must keep behaving exactly that way.
   */
  public double confidenceOf(String slot) {
    if (confidence == null) {
      return 1.0;
    }
    Double value = confidence.get(slot);
    return value == null ? 1.0 : value;
  }

  /** Refinement merge: the newer turn's grade wins; slots it did not touch keep the prior's. */
  public static Map<String, Double> mergeConfidence(Map<String, Double> prior, Map<String, Double> next) {
    if (prior == null && next == null) {
      return null;
    }
    Map<String, Double> merged = new LinkedHashMap<>();
    if (prior != null) {
      merged.putAll(prior);
    }
    if (next != null) {
      merged.putAll(next);
    }
    return merged;
  }
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=SearchIntentConfidenceTest` → 6 pass.
Run: `./mvnw -q test -Dtest=IntentGoldenTest,MockIntentLlmTest,MatchScorerTest` → 2 + 5 + 11 pass (the new component is additive; nothing reads it yet).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/SearchIntent.java backend/src/test/java/com/flatmaite/search/SearchIntentConfidenceTest.java
git commit -m "$(cat <<'EOF'
Let an intent carry how confident each slot is, defaulting to certain

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `IntentGrounding` — can I point at the words?

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/IntentGrounding.java`
- Test: `backend/src/test/java/com/flatmaite/search/IntentGroundingTest.java`

**Interfaces:**
- Produces: `public static Map<String, Double> score(SearchIntent intent, String query, LocalityResolver resolver)`; constants `STATED = 1.0`, `WEAK = 0.75`, `INFERRED = 0.5`.
- Consumes: `Tokens.of`, `NumberWords.parse`, `RentalVocabulary.explicitRoomType`, `LocalityResolver.scan`, Task 1's `SearchIntent.GATED_SLOTS`.

**Note on duplication:** this class re-derives amount spans with its own small patterns rather than calling into `KeywordIntentParser`, whose extraction internals are private and frozen. It asks a different question (is this value *supported by the words*, regardless of who produced it) and must work identically for values the LLM invented, so sharing the parser's private machinery would be the wrong coupling.

- [ ] **Step 1: Write the failing test**

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchIntent.BhkRange;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.seed.SeedLocalities;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Grounding is the difference between what the user said and what the reader guessed. It must be
 * computable from the intent and the query alone — the LLM reports no spans, and its values have to
 * be graded by exactly the rule that grades the keyword parser's.
 */
class IntentGroundingTest {

  private static LocalityResolver resolver;

  @BeforeAll
  static void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(SeedLocalities.entities());
    resolver = new LocalityResolver(repo);
    resolver.reload();
  }

  private static UUID id(String name) {
    return SeedLocalities.id(name);
  }

  private static Map<String, Double> score(SearchIntent intent, String query) {
    return IntentGrounding.score(intent.toBuilder().originalQuery(query).build(), query, resolver);
  }

  @Test
  void statedBudget_isFullyGrounded_inEveryNotation() {
    assertThat(score(SearchIntent.builder().budgetMax(25000).build(), "room under 25k"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().budgetMax(25000).build(), "room under 25,000"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().budgetMin(30000).build(), "flat more than 30000"))
        .containsEntry("budgetMin", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().budgetMax(150000).build(), "one and a half lakh flat"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
  }

  @Test
  void aBudgetNoWordSupports_isInferred() {
    assertThat(score(SearchIntent.builder().budgetMax(25000).build(), "private room in powai"))
        .containsEntry("budgetMax", IntentGrounding.INFERRED);
  }

  @Test
  void roomType_hasThreeLevels() {
    // stated outright
    assertThat(score(SearchIntent.builder().roomType(RoomType.PRIVATE).build(), "private room in goregaon"))
        .containsEntry("roomType", IntentGrounding.STATED);
    // a bare occupancy noun supports it, but the mapping is our convention
    assertThat(score(SearchIntent.builder().roomType(RoomType.PRIVATE).build(), "room near bkc under 25k"))
        .containsEntry("roomType", IntentGrounding.WEAK);
    // pure shape inference — the reported bug
    assertThat(score(SearchIntent.builder().roomType(RoomType.ENTIRE).build(), "2bhk in powai under 40k"))
        .containsEntry("roomType", IntentGrounding.INFERRED);
  }

  @Test
  void localities_carryTheResolversOwnConfidence() {
    SearchIntent exact =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();
    assertThat(score(exact, "flats in powai")).containsEntry("locations", 1.0);

    SearchIntent fuzzy =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();
    assertThat(score(fuzzy, "flats in powaii").get("locations"))
        .isBetween(LocalityResolver.FUZZY_THRESHOLD, LocalityResolver.CONFIDENT);
  }

  @Test
  void aPlaceTheModelSuppliedButTheQueryNeverNames_isInferred() {
    SearchIntent invented =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();
    assertThat(score(invented, "somewhere with a gym")).containsEntry("locations", IntentGrounding.INFERRED);
  }

  @Test
  void commuteRadius_isGradedApartFromItsAnchor() {
    SearchIntent stated =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", id("BKC"), 20)).build();
    Map<String, Double> s = score(stated, "room within 20 min of bkc");
    assertThat(s).containsEntry("commuteTo", 1.0);
    assertThat(s).containsEntry("commuteTo.maxMinutes", IntentGrounding.STATED);

    SearchIntent defaulted =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", id("BKC"), 30)).build();
    Map<String, Double> d = score(defaulted, "room near bkc");
    assertThat(d).containsEntry("commuteTo", 1.0);
    assertThat(d).containsEntry("commuteTo.maxMinutes", IntentGrounding.INFERRED);
  }

  @Test
  void bhkFurnishingGenderVerified_areStatedOnlyWhenTheWordsAppear() {
    Map<String, Double> stated =
        score(
            SearchIntent.builder()
                .bhk(new BhkRange(2, 2))
                .furnished(Furnishing.FULLY_FURNISHED)
                .genderPreference(GenderPreference.FEMALE_ONLY)
                .verifiedOnly(true)
                .build(),
            "fully furnished 2bhk for girls, only verified listings");
    assertThat(stated)
        .containsEntry("bhk", IntentGrounding.STATED)
        .containsEntry("furnished", IntentGrounding.STATED)
        .containsEntry("genderPreference", IntentGrounding.STATED)
        .containsEntry("verifiedOnly", IntentGrounding.STATED);

    Map<String, Double> guessed =
        score(
            SearchIntent.builder()
                .bhk(new BhkRange(2, 2))
                .furnished(Furnishing.FULLY_FURNISHED)
                .build(),
            "nice place in powai");
    assertThat(guessed)
        .containsEntry("bhk", IntentGrounding.INFERRED)
        .containsEntry("furnished", IntentGrounding.INFERRED);
  }

  @Test
  void lifestyle_isGradedAcrossItsFacets() {
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").build())
                    .build(),
                "no smokers in andheri"))
        .containsEntry("lifestyle", IntentGrounding.STATED);
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(
                        SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").diet("VEGETARIAN").build())
                    .build(),
                "no smokers in andheri"))
        .containsEntry("lifestyle", IntentGrounding.WEAK);
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().diet("VEGETARIAN").build())
                    .build(),
                "1bhk in matunga"))
        .containsEntry("lifestyle", IntentGrounding.INFERRED);
  }

  @Test
  void onlyNonNullSlotsAreGraded_andEveryKeyIsAKnownSlot() {
    Map<String, Double> s = score(SearchIntent.builder().budgetMax(25000).build(), "under 25k");
    assertThat(s).containsOnlyKeys("budgetMax");
    assertThat(SearchIntent.GATED_SLOTS).containsAll(s.keySet());
  }

  @Test
  void aNullOrBlankQuery_gradesEverythingInferred_ratherThanThrowing() {
    SearchIntent intent = SearchIntent.builder().roomType(RoomType.PRIVATE).budgetMax(25000).build();
    assertThat(IntentGrounding.score(intent, null, resolver))
        .containsEntry("roomType", IntentGrounding.INFERRED)
        .containsEntry("budgetMax", IntentGrounding.INFERRED);
    assertThat(IntentGrounding.score(intent, "   ", resolver)).isNotEmpty();
  }

  @Test
  void searchTargetAndFreeText_areNeverGraded() {
    Map<String, Double> s =
        score(
            SearchIntent.builder().searchTarget(SearchTarget.FLATMATES).freeText("sea view").build(),
            "flatmate with a sea view");
    assertThat(s).doesNotContainKeys("searchTarget", "freeText", "originalQuery");
  }

  @Test
  void amountsAreMatchedWithinTolerance_notByStringEquality() {
    // "40k" renders 40000 exactly; a model that returns 40000 for "40 thousand" is still grounded
    assertThat(score(SearchIntent.builder().budgetMax(40000).build(), "budget 40 thousand"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().maxDeposit(200000).build(), "2 lakh deposit, 30k rent"))
        .containsEntry("maxDeposit", IntentGrounding.STATED);
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q test -Dtest=IntentGroundingTest`
Expected: compilation failure — `IntentGrounding` does not exist.

- [ ] **Step 3: Implement**

```java
package com.flatmaite.search;

import com.flatmaite.common.domain.RoomType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades how directly the user's own words support each filled slot. The question is deliberately
 * not "did our parser produce this" but "can I point at the span that says it" — the LLM reports no
 * spans, so its values and the keyword parser's must be graded by one rule.
 *
 * <p>Three levels and nothing between them: {@link #STATED} when a span says it outright,
 * {@link #WEAK} when a span supports it through a product convention (bare "room" meaning a private
 * room), {@link #INFERRED} when nothing in the query does — a shape guess, a default, or the
 * model's own reading. Only {@link #STATED} and {@link #WEAK} survive
 * {@link ConfidenceGate#HARD_THRESHOLD} as hard filters.
 */
public final class IntentGrounding {

  public static final double STATED = 1.0;
  public static final double WEAK = 0.75;
  public static final double INFERRED = 0.5;

  private static final Pattern AMOUNT_K = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k\\b");
  private static final Pattern AMOUNT_LAKH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:lakhs?|lac|l)\\b");
  private static final Pattern AMOUNT_PLAIN = Pattern.compile("\\b(\\d[\\d,]{3,8})\\b");
  private static final Pattern MINUTES = Pattern.compile("\\b\\d{1,3}\\s*(?:min|mins|minute|minutes)\\b");
  private static final Pattern BHK_WORD = Pattern.compile("\\b\\d\\s*bhk\\b|\\b1\\s*rk\\b|\\bstudio\\b");
  private static final Pattern DATE_WORD =
      Pattern.compile(
          "\\b(\\d{1,2}[/-]\\d{1,2}|\\d{4}-\\d{2}|today|tomorrow|asap|immediately|next month|"
              + "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\b");
  private static final Pattern ROOM_NOUN = Pattern.compile("\\b(room|rooms|pg|paying guest)\\b");

  private static final Map<String, String[]> FURNISHING_CUES =
      Map.of(
          "FULLY_FURNISHED", new String[] {"fully furnished", "full furnished", "furnished"},
          "SEMI_FURNISHED", new String[] {"semi furnished", "semi-furnished", "semifurnished"},
          "UNFURNISHED", new String[] {"unfurnished", "un furnished", "bare shell"});
  private static final Map<String, String[]> GENDER_CUES =
      Map.of(
          "FEMALE_ONLY", new String[] {"female", "girls", "girl", "ladies", "women", "ladki"},
          "MALE_ONLY", new String[] {"male", "boys", "boy", "men", "gents", "ladka"},
          "ANY", new String[] {"any gender", "anyone"});
  private static final Map<String, String[]> LIFESTYLE_CUES =
      Map.of(
          "smoking", new String[] {"smok", "cigarette"},
          "diet", new String[] {"veg", "non-veg", "nonveg", "eggetarian", "jain"},
          "pets", new String[] {"pet", "dog", "cat"},
          "quiet", new String[] {"quiet", "peaceful", "no parties", "silent"},
          "drinking", new String[] {"drink", "alcohol", "teetotal"},
          "sleepSchedule", new String[] {"early", "night owl", "late night"},
          "cleanliness", new String[] {"tidy", "clean", "neat"},
          "wfh", new String[] {"wfh", "work from home", "remote"},
          "partiesOk", new String[] {"part", "guests"});
  private static final String[] COUPLE_CUES = {"couple", "couples", "married"};
  private static final String[] VERIFIED_CUES = {"verified", "verification"};

  private IntentGrounding() {}

  /** Grades every non-null gated slot. Never throws: an unusable query grades everything INFERRED. */
  public static Map<String, Double> score(SearchIntent intent, String query, LocalityResolver resolver) {
    String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
    Map<String, Double> out = new LinkedHashMap<>();

    Set<Integer> amounts = amountsIn(q);
    List<LocalityResolver.Match> matches = q.isBlank() ? List.of() : resolver.scan(q);

    if (intent.locations() != null && !intent.locations().isEmpty()) {
      out.put("locations", placeGrade(intent.locations(), matches));
    }
    if (intent.excludeLocations() != null && !intent.excludeLocations().isEmpty()) {
      out.put("excludeLocations", placeGrade(intent.excludeLocations(), matches));
    }
    if (intent.budgetMin() != null) {
      out.put("budgetMin", amounts.contains(intent.budgetMin()) ? STATED : INFERRED);
    }
    if (intent.budgetMax() != null) {
      out.put("budgetMax", amounts.contains(intent.budgetMax()) ? STATED : INFERRED);
    }
    if (intent.maxDeposit() != null) {
      out.put("maxDeposit", amounts.contains(intent.maxDeposit()) ? STATED : INFERRED);
    }
    if (intent.roomType() != null) {
      out.put("roomType", roomTypeGrade(intent.roomType(), q));
    }
    if (intent.listingTypes() != null && !intent.listingTypes().isEmpty()) {
      boolean all =
          intent.listingTypes().stream()
              .allMatch(t -> q.contains(t.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
      out.put("listingTypes", all ? STATED : INFERRED);
    }
    if (intent.furnished() != null) {
      out.put("furnished", containsAny(q, FURNISHING_CUES.get(intent.furnished().name())) ? STATED : INFERRED);
    }
    if (intent.bhk() != null && (intent.bhk().min() != null || intent.bhk().max() != null)) {
      out.put("bhk", BHK_WORD.matcher(q).find() ? STATED : INFERRED);
    }
    if (intent.moveInDate() != null) {
      out.put("moveInDate", DATE_WORD.matcher(q).find() ? STATED : INFERRED);
    }
    if (intent.genderPreference() != null) {
      out.put(
          "genderPreference",
          containsAny(q, GENDER_CUES.get(intent.genderPreference().name())) ? STATED : INFERRED);
    }
    if (intent.couplesOk() != null) {
      out.put("couplesOk", containsAny(q, COUPLE_CUES) ? STATED : INFERRED);
    }
    if (intent.amenities() != null && !intent.amenities().isEmpty()) {
      boolean all = intent.amenities().stream().allMatch(a -> q.contains(a.toLowerCase(Locale.ROOT)));
      out.put("amenities", all ? STATED : INFERRED);
    }
    if (intent.lifestyle() != null) {
      out.put("lifestyle", lifestyleGrade(intent.lifestyle(), q));
    }
    if (intent.commuteTo() != null) {
      out.put("commuteTo", commuteAnchorGrade(intent.commuteTo(), matches));
      out.put("commuteTo.maxMinutes", MINUTES.matcher(q).find() ? STATED : INFERRED);
    }
    if (intent.verifiedOnly() != null) {
      out.put("verifiedOnly", containsAny(q, VERIFIED_CUES) ? STATED : INFERRED);
    }
    return out;
  }

  /** The best resolver confidence among the matches that could have produced these refs. */
  private static double placeGrade(List<SearchIntent.LocationRef> refs, List<LocalityResolver.Match> matches) {
    double best = INFERRED;
    for (SearchIntent.LocationRef ref : refs) {
      for (LocalityResolver.Match m : matches) {
        if (mentions(m, ref)) {
          best = Math.max(best, m.confidence());
        }
      }
    }
    return best;
  }

  private static double commuteAnchorGrade(SearchIntent.CommuteTo commute, List<LocalityResolver.Match> matches) {
    double best = INFERRED;
    for (LocalityResolver.Match m : matches) {
      boolean sameId = commute.localityId() != null && m.localityIds().contains(commute.localityId());
      boolean sameName =
          commute.place() != null && m.canonicalName().equalsIgnoreCase(commute.place().trim());
      if (sameId || sameName) {
        best = Math.max(best, m.confidence());
      }
    }
    return best;
  }

  private static boolean mentions(LocalityResolver.Match m, SearchIntent.LocationRef ref) {
    UUID id = ref.localityId();
    if (id != null && m.localityIds().contains(id)) {
      return true;
    }
    return ref.name() != null && m.canonicalName().equalsIgnoreCase(ref.name().trim());
  }

  private static double roomTypeGrade(RoomType type, String q) {
    if (type == RentalVocabulary.explicitRoomType(q)) {
      return STATED;
    }
    // a bare occupancy noun supports PRIVATE/SHARED; "2bhk" alone supports nothing
    if ((type == RoomType.PRIVATE || type == RoomType.SHARED) && ROOM_NOUN.matcher(q).find()) {
      return WEAK;
    }
    return INFERRED;
  }

  private static double lifestyleGrade(SearchIntent.Lifestyle lifestyle, String q) {
    int stated = 0;
    int total = 0;
    for (Map.Entry<String, String[]> e : LIFESTYLE_CUES.entrySet()) {
      if (facetOf(lifestyle, e.getKey()) == null) {
        continue;
      }
      total++;
      if (containsAny(q, e.getValue())) {
        stated++;
      }
    }
    if (total == 0 || stated == 0) {
      return INFERRED;
    }
    return stated == total ? STATED : WEAK;
  }

  private static Object facetOf(SearchIntent.Lifestyle l, String facet) {
    return switch (facet) {
      case "smoking" -> l.smoking();
      case "diet" -> l.diet();
      case "pets" -> l.pets();
      case "quiet" -> l.quiet();
      case "drinking" -> l.drinking();
      case "sleepSchedule" -> l.sleepSchedule();
      case "cleanliness" -> l.cleanliness();
      case "wfh" -> l.wfh();
      case "partiesOk" -> l.partiesOk();
      default -> null;
    };
  }

  /** Every rupee amount the query states, in every notation the parser accepts. */
  private static Set<Integer> amountsIn(String q) {
    List<Integer> out = new ArrayList<>();
    Matcher k = AMOUNT_K.matcher(q);
    while (k.find()) {
      out.add((int) Math.round(Double.parseDouble(k.group(1)) * 1000));
    }
    Matcher lakh = AMOUNT_LAKH.matcher(q);
    while (lakh.find()) {
      out.add((int) Math.round(Double.parseDouble(lakh.group(1)) * 100_000));
    }
    Matcher plain = AMOUNT_PLAIN.matcher(q);
    while (plain.find()) {
      out.add(Integer.parseInt(plain.group(1).replace(",", "")));
    }
    NumberWords.parse(q).ifPresent(out::add);
    for (Tokens.Token t : Tokens.of(q)) {
      NumberWords.parse(t.text()).ifPresent(out::add);
    }
    return Set.copyOf(out);
  }

  private static boolean containsAny(String q, String[] cues) {
    if (cues == null) {
      return false;
    }
    for (String cue : cues) {
      if (q.contains(cue)) {
        return true;
      }
    }
    return false;
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=IntentGroundingTest` → 12 pass.

If a case fails because a helper's real signature differs (`NumberWords.parse` returns `OptionalInt`; `Tokens.Token` accessor names; `RentalVocabulary.explicitRoomType` expects a lower-cased query), fix the call — never the expectation. The one expectation you may adjust is the spelled-amount case if `NumberWords.parse` cannot read a whole phrase: in that case grade it from the token scan and say so in your report.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/IntentGrounding.java backend/src/test/java/com/flatmaite/search/IntentGroundingTest.java
git commit -m "$(cat <<'EOF'
Grade each slot by whether the user's own words support it

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Attach confidence on every extraction path

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` (`extractIntent` ~line 87, `resolveLocalities` ~line 136)
- Test: `backend/src/test/java/com/flatmaite/search/SearchPipelineConfidenceTest.java` (new, pure)

**Interfaces:**
- Consumes: Task 1 (`mergeConfidence`, `confidenceOf`), Task 2 (`IntentGrounding.score`).
- Produces: every intent leaving `extractIntent` carries a confidence map. The provider adapter may pre-fill `intent.confidence()` with its own self-rating (Task 6); this task takes `min` with grounding wherever both exist.

- [ ] **Step 1: Write the failing test**

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.RoomType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The model's opinion of itself never raises a grade — only the user's words can. */
class SearchPipelineConfidenceTest {

  @Test
  void selfRatingAndGrounding_combineByMinimum() {
    Map<String, Double> grounding = Map.of("roomType", 0.5, "budgetMax", 1.0, "bhk", 1.0);
    Map<String, Double> selfRating = Map.of("roomType", 0.9, "budgetMax", 0.4);

    Map<String, Double> merged = SearchPipeline.combineConfidence(grounding, selfRating);

    assertThat(merged).containsEntry("roomType", 0.5); // grounding is lower
    assertThat(merged).containsEntry("budgetMax", 0.4); // the model doubts its own read
    assertThat(merged).containsEntry("bhk", 1.0); // no self-rating offered
  }

  @Test
  void aSelfRatingForASlotWithNoGrounding_isIgnored() {
    Map<String, Double> merged =
        SearchPipeline.combineConfidence(Map.of("roomType", 1.0), Map.of("furnished", 0.2));
    assertThat(merged).containsOnlyKeys("roomType");
  }

  @Test
  void selfRatingsAreClampedAndNullsTolerated() {
    Map<String, Double> merged =
        SearchPipeline.combineConfidence(
            Map.of("roomType", 1.0, "bhk", 1.0), java.util.Collections.singletonMap("roomType", 5.0));
    assertThat(merged).containsEntry("roomType", 1.0).containsEntry("bhk", 1.0);
    assertThat(SearchPipeline.combineConfidence(Map.of("roomType", 1.0), null))
        .containsEntry("roomType", 1.0);
    assertThat(SearchPipeline.combineConfidence(null, Map.of("roomType", 0.2))).isNull();
  }

  @Test
  void aRefinementKeepsThePriorsGradeForSlotsItDidNotTouch() {
    SearchIntent prior =
        SearchIntent.builder()
            .roomType(RoomType.PRIVATE)
            .budgetMax(40000)
            .confidence(Map.of("roomType", 0.5, "budgetMax", 1.0))
            .build();
    SearchIntent next =
        SearchIntent.builder()
            .roomType(RoomType.PRIVATE)
            .budgetMax(30000)
            .confidence(Map.of("budgetMax", 1.0))
            .build();

    Map<String, Double> merged = SearchIntent.mergeConfidence(prior.confidence(), next.confidence());

    assertThat(merged).containsEntry("roomType", 0.5).containsEntry("budgetMax", 1.0);
  }
}
```

- [ ] **Step 2: Run to verify failure** — `./mvnw -q test -Dtest=SearchPipelineConfidenceTest` → compilation failure (`combineConfidence` missing).

- [ ] **Step 3: Implement**

In `SearchPipeline`, add the combiner and a grading step, then call it from every path.

```java
  /**
   * The model may rate its own read of the user's words; that rating can only lower a grade, never
   * raise one. Slots the words never grounded are not resurrected by the model's confidence in them.
   */
  static Map<String, Double> combineConfidence(Map<String, Double> grounding, Map<String, Double> selfRating) {
    if (grounding == null) {
      return null;
    }
    Map<String, Double> out = new LinkedHashMap<>(grounding);
    if (selfRating != null) {
      for (Map.Entry<String, Double> e : selfRating.entrySet()) {
        Double self = e.getValue();
        Double ground = out.get(e.getKey());
        if (self == null || ground == null) {
          continue;
        }
        out.put(e.getKey(), Math.min(ground, Math.max(0.0, Math.min(1.0, self))));
      }
    }
    return out;
  }

  /**
   * Grades an extracted intent against the words that produced it. Runs on every path — LLM, mock,
   * heuristic, cache — so one rule decides what is enforced, whoever did the extracting. Fails
   * closed: if grading throws, the intent keeps whatever it had and everything stays hard.
   */
  private SearchIntent withConfidence(SearchIntent intent, String query, SearchIntent prior) {
    if (intent == null) {
      return null;
    }
    Map<String, Double> graded;
    try {
      graded = combineConfidence(IntentGrounding.score(intent, query, localityResolver), intent.confidence());
    } catch (RuntimeException e) {
      log.warn("Confidence grading failed, keeping every slot hard: {}", e.getMessage());
      return intent;
    }
    Map<String, Double> merged =
        prior == null ? graded : SearchIntent.mergeConfidence(prior.confidence(), graded);
    return intent.toBuilder().confidence(merged).build();
  }
```

Then in `extractIntent`, wrap each returned intent (the existing `resolveLocalities(...)` calls are unchanged; grading happens around them):

- heuristic path: `return new IntentLlm.Extraction(withConfidence(resolveLocalities(heuristic), query, prior), IntentLlm.Mode.NONE);`
- cache path: unchanged — the cached `Extraction` was graded before it was cached.
- LLM/fallback path: `IntentLlm.Extraction resolved = new IntentLlm.Extraction(withConfidence(resolveLocalities(extracted.intent()), query, prior), extracted.mode());`

Add `import java.util.LinkedHashMap;` and `import java.util.Map;` if absent.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=SearchPipelineConfidenceTest` → 4 pass.
Run: `./mvnw -q test -Dtest=IntentGoldenTest` → 2 pass (grading must change no extracted value).
Run: `./mvnw -q test -Dtest=SearchPipelineIntegrationTest` (Docker) → 7 pass (nothing consumes confidence yet).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/test/java/com/flatmaite/search/SearchPipelineConfidenceTest.java
git commit -m "$(cat <<'EOF'
Grade every extracted intent, whichever path produced it

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `ConfidenceGate` and the gated `toFilters`

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/ConfidenceGate.java`
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` (`toFilters` ~line 284–316, `admittedLocalityIds`, `retrieveListings` ~line 64)
- Test: `backend/src/test/java/com/flatmaite/search/HybridRetrieverGatingTest.java`

**Interfaces:**
- Produces: `ConfidenceGate.HARD_THRESHOLD`, `ALWAYS_HARD`, `SCORED_ELSEWHERE`, `isHard(SearchIntent, String)`, `softSlots(SearchIntent)`, `preferenceSlots(SearchIntent)`; `HybridRetriever.toFiltersWithRadius(SearchIntent, Integer)`, `retrieveListings(SearchIntent, Integer)`.
- Consumes: Task 1's `confidenceOf`/`GATED_SLOTS`.

- [ ] **Step 1: Write the failing test**

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A guess must not delete listings. What the user stated still filters; what the reader inferred
 * only ranks — except for the two promises that are never softened.
 */
class HybridRetrieverGatingTest {

  private static final UUID POWAI = UUID.randomUUID();

  @Test
  void anInferredSlotIsNotAHardFilter_aStatedOneIs() {
    SearchIntent inferred =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .confidence(Map.of("roomType", 0.5, "furnished", 1.0))
            .build();

    assertThat(ConfidenceGate.isHard(inferred, "roomType")).isFalse();
    assertThat(ConfidenceGate.isHard(inferred, "furnished")).isTrue();
    assertThat(ConfidenceGate.softSlots(inferred)).containsExactly("roomType");
  }

  @Test
  void theThresholdIsInclusive_soAWeakGradeStillFilters() {
    SearchIntent weak =
        SearchIntent.builder().roomType(RoomType.PRIVATE).confidence(Map.of("roomType", 0.75)).build();
    assertThat(ConfidenceGate.isHard(weak, "roomType")).isTrue();
    assertThat(ConfidenceGate.softSlots(weak)).isEmpty();
  }

  @Test
  void exclusionsAndVerifiedOnly_areNeverSoftened() {
    SearchIntent doubted =
        SearchIntent.builder()
            .excludeLocations(List.of(new LocationRef("Powai", POWAI)))
            .verifiedOnly(true)
            .confidence(Map.of("excludeLocations", 0.1, "verifiedOnly", 0.1))
            .build();
    assertThat(ConfidenceGate.isHard(doubted, "excludeLocations")).isTrue();
    assertThat(ConfidenceGate.isHard(doubted, "verifiedOnly")).isTrue();
    assertThat(ConfidenceGate.softSlots(doubted)).isEmpty();
  }

  @Test
  void anUngradedIntentBehavesExactlyAsBefore() {
    SearchIntent old = SearchIntent.builder().roomType(RoomType.ENTIRE).budgetMax(30000).build();
    assertThat(ConfidenceGate.softSlots(old)).isEmpty();
    assertThat(ConfidenceGate.isHard(old, "roomType")).isTrue();
  }

  @Test
  void preferenceSlots_excludeWhatAnExistingComponentAlreadyScores() {
    SearchIntent intent =
        SearchIntent.builder()
            .budgetMax(30000)
            .roomType(RoomType.ENTIRE)
            .locations(List.of(new LocationRef("Powai", POWAI)))
            .confidence(Map.of("budgetMax", 0.5, "roomType", 0.5, "locations", 0.5))
            .build();

    assertThat(ConfidenceGate.softSlots(intent)).containsExactly("locations", "budgetMax", "roomType");
    // budgetFit and location already rank those two; only roomType needs a new component
    assertThat(ConfidenceGate.preferenceSlots(intent)).containsExactly("roomType");
  }

  @Test
  void softSlotsFollowTheCanonicalOrder() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .budgetMax(30000)
            .furnished(Furnishing.SEMI_FURNISHED)
            .confidence(Map.of("roomType", 0.5, "budgetMax", 0.5, "furnished", 0.5))
            .build();
    assertThat(ConfidenceGate.softSlots(intent)).containsExactly("budgetMax", "roomType", "furnished");
  }
}
```

Plus, in the same file, gating through the real `toFilters` — construct `HybridRetriever` exactly as `HybridRetrieverAdmissionTest` already does (copy its `@BeforeEach` wiring: mocked `LocalityRepository` returning `SeedLocalities.entities()`, a real `LocalityResolver` + `CommuteEstimator` reloaded, mocked query services, real `FlatmaiteProperties`):

```java
  @Test
  void aSoftSlotNeverReachesTheSqlFilters() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .confidence(Map.of("roomType", 0.5, "furnished", 1.0))
            .build();

    ListingFilters filters = retriever.toFilters(intent);

    assertThat(filters.roomType()).isNull();
    assertThat(filters.furnishings()).containsExactly(Furnishing.FULLY_FURNISHED);
  }

  @Test
  void alertsAreGatedTheSameWay() {
    SearchIntent intent =
        SearchIntent.builder().roomType(RoomType.ENTIRE).confidence(Map.of("roomType", 0.5)).build();
    assertThat(retriever.toFilters(intent, false).roomType()).isNull();
  }

  @Test
  void aWiderRescueRadiusAdmitsMoreLocalities() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of(new LocationRef("Goregaon", SeedLocalities.id("Goregaon")))).build();
    int normal = retriever.toFilters(intent).localityIds().size();
    int wide = retriever.toFiltersWithRadius(intent, 45).localityIds().size();
    assertThat(wide).isGreaterThan(normal);
  }
```

- [ ] **Step 2: Run to verify failure** — `./mvnw -q test -Dtest=HybridRetrieverGatingTest` → compilation failure.

- [ ] **Step 3: Implement `ConfidenceGate`**

```java
package com.flatmaite.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which slots are enforced as SQL filters and which only rank. A slot the user's words
 * grounded stays a filter; one the reader inferred becomes a preference, so a guess can no longer
 * delete a listing the user would have wanted.
 *
 * <p>Two slots are never softened however doubtful the grade: an exclusion the user typed is a
 * promise, and quietly surfacing unverified listings to someone who asked for verified ones is the
 * opposite of what this product sells. Both are stated outright in practice — the set is a
 * guarantee, not a workaround.
 */
public final class ConfidenceGate {

  public static final double HARD_THRESHOLD = 0.75;
  public static final Set<String> ALWAYS_HARD = Set.of("excludeLocations", "verifiedOnly");

  /** Soft slots an existing score component already ranks — dropping the filter is enough. */
  public static final Set<String> SCORED_ELSEWHERE =
      Set.of("budgetMin", "budgetMax", "maxDeposit", "locations", "commuteTo", "commuteTo.maxMinutes", "lifestyle");

  private ConfidenceGate() {}

  public static boolean isHard(SearchIntent intent, String slot) {
    return ALWAYS_HARD.contains(slot) || intent.confidenceOf(slot) >= HARD_THRESHOLD;
  }

  /** Non-null slots that are not enforced, in {@link SearchIntent#GATED_SLOTS} order. */
  public static List<String> softSlots(SearchIntent intent) {
    List<String> out = new ArrayList<>();
    for (String slot : SearchIntent.GATED_SLOTS) {
      if (isPresent(intent, slot) && !isHard(intent, slot)) {
        out.add(slot);
      }
    }
    return out;
  }

  /** Soft slots with no existing score component — these become the `preferences` component. */
  public static List<String> preferenceSlots(SearchIntent intent) {
    return softSlots(intent).stream().filter(s -> !SCORED_ELSEWHERE.contains(s)).toList();
  }

  static boolean isPresent(SearchIntent intent, String slot) {
    return switch (slot) {
      case "locations" -> intent.locations() != null && !intent.locations().isEmpty();
      case "excludeLocations" -> intent.excludeLocations() != null && !intent.excludeLocations().isEmpty();
      case "budgetMin" -> intent.budgetMin() != null;
      case "budgetMax" -> intent.budgetMax() != null;
      case "maxDeposit" -> intent.maxDeposit() != null;
      case "roomType" -> intent.roomType() != null;
      case "listingTypes" -> intent.listingTypes() != null && !intent.listingTypes().isEmpty();
      case "furnished" -> intent.furnished() != null;
      case "bhk" -> intent.bhk() != null && (intent.bhk().min() != null || intent.bhk().max() != null);
      case "moveInDate" -> intent.moveInDate() != null;
      case "genderPreference" -> intent.genderPreference() != null;
      case "couplesOk" -> intent.couplesOk() != null;
      case "amenities" -> intent.amenities() != null && !intent.amenities().isEmpty();
      case "lifestyle" -> intent.lifestyle() != null;
      case "commuteTo", "commuteTo.maxMinutes" -> intent.commuteTo() != null;
      case "verifiedOnly" -> intent.verifiedOnly() != null;
      default -> false;
    };
  }

  /** Display label for a slot, used in notes and near-miss reasons. */
  public static String label(String slot) {
    return switch (slot) {
      case "locations" -> "area";
      case "excludeLocations" -> "excluded areas";
      case "budgetMin" -> "minimum budget";
      case "budgetMax" -> "budget";
      case "maxDeposit" -> "deposit";
      case "roomType" -> "room type";
      case "listingTypes" -> "listing type";
      case "furnished" -> "furnishing";
      case "bhk" -> "size";
      case "moveInDate" -> "move-in date";
      case "genderPreference" -> "gender preference";
      case "couplesOk" -> "couples";
      case "amenities" -> "amenities";
      case "lifestyle" -> "lifestyle";
      case "commuteTo" -> "commute";
      case "commuteTo.maxMinutes" -> "commute time";
      case "verifiedOnly" -> "verified only";
      default -> slot;
    };
  }
}
```

- [ ] **Step 4: Gate `toFilters` and generalise the radius**

In `HybridRetriever`, replace the three `toFilters` members with:

```java
  public ListingFilters toFilters(SearchIntent intent) {
    return toFiltersWithRadius(intent, props.getSearch().getNearbyRadiusMinutes());
  }

  /** Saved-search alerts pass {@code false}: an alert cannot explain a widened area. */
  public ListingFilters toFilters(SearchIntent intent, boolean widenToNearby) {
    return toFiltersWithRadius(intent, widenToNearby ? props.getSearch().getNearbyRadiusMinutes() : null);
  }

  /**
   * Maps intent → hard filters, honouring the confidence gate: a slot the reader only inferred is
   * left out entirely, so it ranks (via {@link MatchScorer}) instead of deleting rows. Budget keeps
   * its ×1.1 headroom — near-misses surface as concerns.
   *
   * @param radiusMinutes how far a named locality's neighbourhood reaches; null = strict (alerts).
   */
  public ListingFilters toFiltersWithRadius(SearchIntent intent, Integer radiusMinutes) {
    SearchIntent.Lifestyle lifestyle = intent.lifestyleOrEmpty();
    boolean hardLocations = ConfidenceGate.isHard(intent, "locations");
    ListingFilters.ListingFiltersBuilder b =
        ListingFilters.builder()
            .localityIds(hardLocations ? admittedLocalityIds(intent, radiusMinutes) : List.of())
            .excludeLocalityIds(excludedLocalityIds(intent));
    if (ConfidenceGate.isHard(intent, "budgetMin")) {
      b.budgetMin(intent.budgetMin());
    }
    if (ConfidenceGate.isHard(intent, "budgetMax")) {
      b.budgetMax(intent.budgetMax() == null ? null : (int) (intent.budgetMax() * 1.1));
    }
    if (ConfidenceGate.isHard(intent, "maxDeposit")) {
      b.maxDeposit(intent.maxDeposit());
    }
    if (ConfidenceGate.isHard(intent, "roomType")) {
      b.roomType(intent.roomType());
    }
    if (ConfidenceGate.isHard(intent, "listingTypes")) {
      b.listingTypes(intent.listingTypes());
    }
    if (ConfidenceGate.isHard(intent, "furnished")) {
      b.furnishings(intent.furnished() == null ? null : List.of(intent.furnished()));
    }
    if (ConfidenceGate.isHard(intent, "bhk")) {
      b.bhkMin(intent.bhk() == null ? null : intent.bhk().min())
          .bhkMax(intent.bhk() == null ? null : intent.bhk().max());
    }
    if (ConfidenceGate.isHard(intent, "moveInDate")) {
      b.moveInBy(parseMoveIn(intent.moveInDate()));
    }
    if (ConfidenceGate.isHard(intent, "genderPreference")) {
      b.genderPref(intent.genderPreference());
    }
    if (ConfidenceGate.isHard(intent, "amenities")) {
      b.amenitySlugs(intent.amenities());
    }
    if (ConfidenceGate.isHard(intent, "couplesOk")) {
      b.couplesAllowed(intent.couplesOk());
    }
    if (ConfidenceGate.isHard(intent, "lifestyle")) {
      b.smokeFreeHousehold("NO_SMOKERS".equals(lifestyle.smoking()))
          .vegHousehold("VEGETARIAN".equals(lifestyle.diet()));
    }
    return b.verifiedOnly(Boolean.TRUE.equals(intent.verifiedOnly())).build();
  }
```

Change `admittedLocalityIds(SearchIntent intent, boolean widenToNearby)` to `admittedLocalityIds(SearchIntent intent, Integer radiusMinutes)` — widen when `radiusMinutes != null`, using that value in place of `props.getSearch().getNearbyRadiusMinutes()`; keep a `boolean` overload delegating as above so existing callers and `HybridRetrieverAdmissionTest` compile unchanged. When the commute anchor carries its own explicit radius, that still wins, exactly as today.

Add the retrieval overload used by the rescue ladder:

```java
  public List<Candidate> retrieveListings(SearchIntent intent) {
    return retrieveListings(intent, props.getSearch().getNearbyRadiusMinutes());
  }

  public List<Candidate> retrieveListings(SearchIntent intent, Integer radiusMinutes) {
    ListingFilters filters = toFiltersWithRadius(intent, radiusMinutes);
    // ... the existing body, unchanged, from the line after the old `toFilters` call ...
  }
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest=HybridRetrieverGatingTest,HybridRetrieverAdmissionTest,ListingQueryServiceWhereTest` → 9 + 6 + 2 pass.
Run: `./mvnw -q test -Dtest=IntentGoldenTest` → 2 pass.
Run: `./mvnw -q test -Dtest=LocationWideningIntegrationTest,SearchPipelineIntegrationTest,HybridRetrieverIntegrationTest` (Docker) → 4 + 7 + 4 pass. **These are the regression gate for this task**: seed queries state their constraints, so nothing should go soft. If one fails because a seed query's slot now grades below 0.75, report the query, the slot and its grade — do not lower the threshold.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/ConfidenceGate.java backend/src/main/java/com/flatmaite/search/HybridRetriever.java backend/src/test/java/com/flatmaite/search/HybridRetrieverGatingTest.java
git commit -m "$(cat <<'EOF'
Stop enforcing inferred slots as SQL filters

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: The `preferences` score component

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/MatchScorer.java` (`scoreListing`, after the existing components, before `finish`)
- Test: `backend/src/test/java/com/flatmaite/search/MatchScorerTest.java` (extend)

**Interfaces:**
- Consumes: `ConfidenceGate.preferenceSlots`, `ConfidenceGate.label`.
- Produces: a `Component("preferences", 0.15, …)` present only when `preferenceSlots` is non-empty.

- [ ] **Step 1: Write the failing tests** (append to `MatchScorerTest`, following its existing candidate-building helpers)

```java
  @Test
  void aSoftPreferenceRanksInsteadOfFiltering() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .confidence(java.util.Map.of("roomType", 0.5))
            .build();

    Scored matching = MatchScorer.scoreListing(intent, candidateWithRoomType(RoomType.ENTIRE));
    Scored missing = MatchScorer.scoreListing(intent, candidateWithRoomType(RoomType.PRIVATE));

    assertThat(component(matching, "preferences").score()).isEqualTo(1.0);
    assertThat(component(matching, "preferences").weight()).isEqualTo(0.15);
    assertThat(component(matching, "preferences").detail()).contains("Matches your preferred room type");
    assertThat(component(missing, "preferences").score()).isEqualTo(0.0);
    assertThat(component(missing, "preferences").detail()).contains("a preference, not a requirement");
    assertThat(missing.score()).isLessThan(matching.score());
  }

  @Test
  void noPreferencesComponentWhenEverySlotIsHard() {
    SearchIntent intent = SearchIntent.builder().roomType(RoomType.ENTIRE).build();
    assertThat(MatchScorer.scoreListing(intent, candidateWithRoomType(RoomType.ENTIRE)).components())
        .extracting(MatchScorer.Component::component)
        .doesNotContain("preferences");
  }

  @Test
  void slotsAnExistingComponentAlreadyScores_getNoPreferencesComponent() {
    SearchIntent intent =
        SearchIntent.builder().budgetMax(30000).confidence(java.util.Map.of("budgetMax", 0.5)).build();
    assertThat(MatchScorer.scoreListing(intent, candidateWithRoomType(RoomType.ENTIRE)).components())
        .extracting(MatchScorer.Component::component)
        .doesNotContain("preferences");
  }

  @Test
  void severalSoftPreferencesScoreAsAFraction() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .confidence(java.util.Map.of("roomType", 0.5, "furnished", 0.5))
            .build();
    // the candidate is ENTIRE but semi-furnished → one of two satisfied
    assertThat(component(MatchScorer.scoreListing(intent, semiFurnishedEntire()), "preferences").score())
        .isEqualTo(0.5);
  }
```

Add the two helpers next to the file's existing builders (`candidateWithRoomType(RoomType)`, `semiFurnishedEntire()`), reusing whatever listing-construction helper `MatchScorerTest` already has, and a `component(Scored, String)` lookup if one is not already present.

- [ ] **Step 2: Run to verify failure** — `./mvnw -q test -Dtest=MatchScorerTest` → the four new tests fail (no `preferences` component).

- [ ] **Step 3: Implement**

In `scoreListing`, immediately before the `return finish(parts);`:

```java
    // preferences (.15) — slots the reader inferred rather than read. They no longer filter, so they
    // rank here instead: a listing that misses every one of them still appears, just lower.
    List<String> preferenceSlots = ConfidenceGate.preferenceSlots(intent);
    if (!preferenceSlots.isEmpty()) {
      List<String> met = new ArrayList<>();
      List<String> missed = new ArrayList<>();
      for (String slot : preferenceSlots) {
        (satisfies(intent, l, slot) ? met : missed).add(ConfidenceGate.label(slot));
      }
      double score = met.size() / (double) preferenceSlots.size();
      String detail =
          missed.isEmpty()
              ? "Matches your preferred %s".formatted(String.join(", ", met))
              : "%s — a preference, not a requirement".formatted(capitalize(String.join(", ", missed)));
      parts.add(new Component("preferences", 0.15, score, detail));
    }
```

and add the predicate beside the other private helpers:

```java
  /** Does this listing satisfy a soft slot? Unknown slots count as satisfied — never penalise. */
  private static boolean satisfies(SearchIntent intent, Listing l, String slot) {
    return switch (slot) {
      case "roomType" -> l.getRoomType() == intent.roomType();
      case "furnished" -> l.getFurnishing() == intent.furnished();
      case "listingTypes" -> intent.listingTypes().contains(l.getType());
      case "bhk" -> {
        Integer bhk = l.getProperty() == null ? null : l.getProperty().getBhk();
        Integer min = intent.bhk().min();
        Integer max = intent.bhk().max();
        yield bhk != null && (min == null || bhk >= min) && (max == null || bhk <= max);
      }
      case "genderPreference" -> l.getGenderPreference() == null
          || l.getGenderPreference() == intent.genderPreference();
      case "couplesOk" -> !Boolean.TRUE.equals(intent.couplesOk()) || Boolean.TRUE.equals(l.getCouplesAllowed());
      case "moveInDate", "amenities" -> true; // not comparable from the card; never penalised
      default -> true;
    };
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
```

Adjust each accessor to the real `Listing`/`Property` getters — read `Listing.java` first and use what exists; if a field is not on the entity, return `true` for that slot and note it in your report rather than adding a query.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=MatchScorerTest` → 15 pass.
Run: `./mvnw -q test -Dtest=SearchPipelineIntegrationTest` (Docker) → 7 pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/MatchScorer.java backend/src/test/java/com/flatmaite/search/MatchScorerTest.java
git commit -m "$(cat <<'EOF'
Rank inferred preferences instead of enforcing them

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: The model rates its own reading

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/ai/OpenAiLlms.java` (`INTENT_RULES`, `intentSystem`, the refine merge)
- Modify: `backend/src/main/java/com/flatmaite/ai/MockLlms.java` (merge `confidence` like every other field)
- Test: `backend/src/test/java/com/flatmaite/ai/OpenAiLlmsPromptTest.java`, `MockIntentLlmTest.java` (extend)

- [ ] **Step 1: Write the failing tests**

In `OpenAiLlmsPromptTest`:

```java
  @Test
  void theIntentPromptAsksTheModelToRateWhatTheUserActuallySaid() {
    String system = OpenAiLlms.intentSystem(List.of("Powai", "BKC"));
    assertThat(system).contains("confidence");
    assertThat(system).contains("1.0 when the user states it outright");
    assertThat(system).contains("0.5 when you inferred it");
  }
```

In `MockIntentLlmTest`:

```java
  @Test
  void refinementKeepsThePriorsConfidenceForUntouchedSlots() {
    SearchIntent prior =
        SearchIntent.builder()
            .budgetMax(40000)
            .roomType(RoomType.PRIVATE)
            .confidence(java.util.Map.of("roomType", 0.5, "budgetMax", 1.0))
            .build();

    SearchIntent merged = llm.extract("make it 30k", prior);

    assertThat(merged.confidenceOf("roomType")).isEqualTo(0.5);
  }
```

- [ ] **Step 2: Run to verify failure** — `./mvnw -q test -Dtest=OpenAiLlmsPromptTest,MockIntentLlmTest` → both new tests fail.

- [ ] **Step 3: Implement**

In `OpenAiLlms.INTENT_RULES`, append one rule verbatim:

```
      Also return "confidence": an object mapping each field you filled to how directly the user's words
      state it — 1.0 when the user states it outright, 0.75 when their words imply it, 0.5 when you inferred
      it from context or convention. Rate the user's words, not your certainty about your own JSON. Omit the
      object entirely if unsure.
```

`SearchIntent` already carries `confidence`, so `BeanOutputConverter` includes it in the generated schema and a returned object lands in `intent.confidence()` with no extra parsing. `SearchPipeline.withConfidence` (Task 3) already mins it against grounding and clamps it — no clamping here.

In `MockLlms.MockIntentLlm`'s refinement merge, add alongside the other fields:

```java
          .confidence(SearchIntent.mergeConfidence(prior.confidence(), parsed.confidence()))
```

Do the same in `OpenAiLlms`'s refine merge if it builds a merged intent field-by-field; if it returns the model's object wholesale, leave it — Task 3's `withConfidence` merges against the prior.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=OpenAiLlmsPromptTest,MockIntentLlmTest,IntentLlmModeTest` → 6 + 6 + 3 pass.
Run: `./mvnw -q test -Dtest=IntentGoldenTest` → 2 pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/ai/OpenAiLlms.java backend/src/main/java/com/flatmaite/ai/MockLlms.java backend/src/test/java/com/flatmaite/ai/OpenAiLlmsPromptTest.java backend/src/test/java/com/flatmaite/ai/MockIntentLlmTest.java
git commit -m "$(cat <<'EOF'
Ask the model how directly the user's words state each field

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Say it out loud — note, relaxer order, chip endorsement

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` (`search`, `computeRelaxers`)
- Modify: `backend/src/main/java/com/flatmaite/search/AiSearchController.java` (`apply`)
- Test: `backend/src/test/java/com/flatmaite/search/AiSearchControllerTest.java` (extend), `SearchPipelineIntegrationTest` (extend)

- [ ] **Step 1: Write the failing test** (in `AiSearchControllerTest`)

```java
  @Test
  void applyingChipsEndorsesEverySlot_soNothingStaysAPreference() {
    UUID sessionId = UUID.randomUUID();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    SearchIntent edited =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .confidence(java.util.Map.of("roomType", 0.5))
            .build();

    controller.apply(
        new AiSearchController.ApplyIntentRequest(sessionId, edited),
        new MockHttpServletRequest(),
        new MockHttpServletResponse());

    ArgumentCaptor<SearchIntent> captor = ArgumentCaptor.forClass(SearchIntent.class);
    verify(pipeline).search(captor.capture(), any(), any(), any());
    assertThat(captor.getValue().confidenceOf("roomType")).isEqualTo(1.0);
  }
```

- [ ] **Step 2: Run to verify failure** — `./mvnw -q test -Dtest=AiSearchControllerTest` → the new test fails.

- [ ] **Step 3: Implement**

`AiSearchController.apply`, immediately after the null check:

```java
    // The user has seen these chips and pressed apply — from here they are filters, not guesses.
    SearchIntent endorsed = body.intent().toBuilder().confidence(null).build();
```

and use `endorsed` in place of `body.intent()` for the rest of the method (pipeline call and `sessions.update`). A null map reads as 1.0 everywhere, which is exactly "the user endorsed all of it" — no map to build.

`SearchPipeline.search`, where `finalNote` is assembled, after the nearby-areas clause:

```java
    List<String> soft = ConfidenceGate.softSlots(intent);
    if (!soft.isEmpty()) {
      String preferences =
          "Some of these are preferences, not filters: %s."
              .formatted(soft.stream().map(ConfidenceGate::label).collect(Collectors.joining(", ")));
      finalNote = finalNote == null ? preferences : finalNote + " " + preferences;
    }
```

`SearchPipeline.computeRelaxers`, before returning: order by ascending confidence of the slot each relaxer relaxes, so the shakiest constraint is offered first. Give each `out.add(...)` its slot name (a local `record Candidate(Relaxer relaxer, String slot)` list, or add relaxers to a list of pairs and sort at the end), sorting by `intent.confidenceOf(slot)` then by the order they were added. Slots: budget → `budgetMax`, minimum → `budgetMin`, verified → `verifiedOnly`, lifestyle → `lifestyle`, all-of-Mumbai → `locations`.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q test -Dtest=AiSearchControllerTest,IntentArbiterTest` → 2 + 5 pass.
Run: `./mvnw -q test -Dtest=SearchPipelineIntegrationTest` (Docker) → 7 pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/main/java/com/flatmaite/search/AiSearchController.java backend/src/test/java/com/flatmaite/search/AiSearchControllerTest.java
git commit -m "$(cat <<'EOF'
Name the preferences in the response and treat applied chips as certain

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: The thin-result rescue ladder

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java`, `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/flatmaite/search/SearchDtos.java` (`AiResult` gains `nearMiss`, `nearMissReason`)
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` (`searchHomes`, `Homes`)
- Create: `backend/src/main/java/com/flatmaite/search/RescueLadder.java`
- Test: `backend/src/test/java/com/flatmaite/search/ThinResultRescueTest.java`; extend `SearchPipelineIntegrationTest`

**Interfaces:**
- Produces: `RescueLadder.rungs(SearchIntent, int rescueRadiusMinutes)` → `List<Rung>` where `record Rung(String slot, SearchIntent intent, Integer radiusMinutes, String reasonTemplate)`; `Rung.slot() == null` marks the radius rung.
- Consumes: `ConfidenceGate`, Task 4's `retrieveListings(intent, radiusMinutes)`.

- [ ] **Step 1: Properties**

`FlatmaiteProperties.Search` gains:

```java
    /** Below this many listings, the pipeline tops the page up with nearby and near-miss results. */
    private int minResults = 6;

    /** The second ring the rescue ladder reaches for before it starts dropping filters. */
    private int rescueRadiusMinutes = 45;
```

`application.yml`, beside `nearby-radius-minutes`:

```yaml
      min-results: ${SEARCH_MIN_RESULTS:6}
      rescue-radius-minutes: ${SEARCH_RESCUE_RADIUS_MINUTES:45}
```

- [ ] **Step 2: Write the failing test**

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A thin page is when the product should work hardest. The ladder is deterministic so the same
 * search always rescues the same way, and it never trades away a promise to fill the page.
 */
class ThinResultRescueTest {

  private static final UUID POWAI = UUID.randomUUID();

  @Test
  void theWiderRingComesFirst_whenAPlaceWasNamed() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", POWAI)))
            .budgetMax(30000)
            .furnished(Furnishing.FULLY_FURNISHED)
            .build();

    List<RescueLadder.Rung> rungs = RescueLadder.rungs(intent, 45);

    assertThat(rungs.get(0).slot()).isNull();
    assertThat(rungs.get(0).radiusMinutes()).isEqualTo(45);
  }

  @Test
  void withNoPlaceNamed_theLadderStartsByDroppingAFilter() {
    SearchIntent intent = SearchIntent.builder().budgetMax(30000).build();
    assertThat(RescueLadder.rungs(intent, 45).get(0).slot()).isEqualTo("budgetMax");
  }

  @Test
  void filtersAreDroppedLeastConfidentFirst_tiesInSlotOrder() {
    SearchIntent intent =
        SearchIntent.builder()
            .budgetMax(30000)
            .furnished(Furnishing.FULLY_FURNISHED)
            .roomType(RoomType.ENTIRE)
            .confidence(Map.of("budgetMax", 1.0, "furnished", 0.8, "roomType", 0.8))
            .build();

    assertThat(RescueLadder.rungs(intent, 45))
        .extracting(RescueLadder.Rung::slot)
        .containsExactly("roomType", "furnished", "budgetMax"); // 0.8 ties break in GATED_SLOTS order
  }

  @Test
  void promisesAreNeverInTheLadder() {
    SearchIntent intent =
        SearchIntent.builder()
            .excludeLocations(List.of(new LocationRef("Powai", POWAI)))
            .verifiedOnly(true)
            .budgetMax(30000)
            .build();
    assertThat(RescueLadder.rungs(intent, 45))
        .extracting(RescueLadder.Rung::slot)
        .containsExactly("budgetMax");
  }

  @Test
  void aSoftSlotIsNotInTheLadder_itIsAlreadyNotFiltering() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .budgetMax(30000)
            .confidence(Map.of("roomType", 0.5))
            .build();
    assertThat(RescueLadder.rungs(intent, 45))
        .extracting(RescueLadder.Rung::slot)
        .containsExactly("budgetMax");
  }

  @Test
  void eachRungsIntentDropsExactlyItsOwnSlot() {
    SearchIntent intent =
        SearchIntent.builder().budgetMax(30000).furnished(Furnishing.FULLY_FURNISHED).build();
    RescueLadder.Rung budget =
        RescueLadder.rungs(intent, 45).stream().filter(r -> "budgetMax".equals(r.slot())).findFirst().orElseThrow();

    assertThat(budget.intent().budgetMax()).isNull();
    assertThat(budget.intent().furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
  }

  @Test
  void anIntentWithNothingToRelax_hasNoLadder() {
    assertThat(RescueLadder.rungs(SearchIntent.builder().build(), 45)).isEmpty();
  }
}
```

- [ ] **Step 3: Implement `RescueLadder`**

```java
package com.flatmaite.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What to try, in order, when the hard-filtered page comes back thin. The widest honest move first —
 * looking further out — and only then giving up a filter, least-confident first, because the
 * constraint the reader was least sure of is the one the user will miss least. Promises
 * ({@link ConfidenceGate#ALWAYS_HARD}) are never on the ladder: a short page is better than a
 * dishonest one.
 */
public final class RescueLadder {

  /** @param slot the filter this rung gives up, or null for the wider-ring rung. */
  public record Rung(String slot, SearchIntent intent, Integer radiusMinutes, String reason) {}

  private RescueLadder() {}

  public static List<Rung> rungs(SearchIntent intent, int rescueRadiusMinutes) {
    List<Rung> out = new ArrayList<>();
    boolean hasPlace =
        ConfidenceGate.isPresent(intent, "locations") || ConfidenceGate.isPresent(intent, "commuteTo");
    if (hasPlace) {
      out.add(new Rung(null, intent, rescueRadiusMinutes, "further out"));
    }
    List<String> droppable =
        SearchIntent.GATED_SLOTS.stream()
            .filter(s -> !ConfidenceGate.ALWAYS_HARD.contains(s))
            .filter(s -> ConfidenceGate.isPresent(intent, s))
            .filter(s -> ConfidenceGate.isHard(intent, s))
            .sorted(
                Comparator.comparingDouble(intent::confidenceOf)
                    .thenComparingInt(SearchIntent.GATED_SLOTS::indexOf))
            .toList();
    for (String slot : droppable) {
      out.add(new Rung(slot, without(intent, slot), null, ConfidenceGate.label(slot)));
    }
    return out;
  }

  /** The same intent with one slot cleared — everything else still enforced. */
  static SearchIntent without(SearchIntent intent, String slot) {
    SearchIntent.SearchIntentBuilder b = intent.toBuilder();
    switch (slot) {
      case "locations" -> b.locations(null);
      case "budgetMin" -> b.budgetMin(null);
      case "budgetMax" -> b.budgetMax(null);
      case "maxDeposit" -> b.maxDeposit(null);
      case "roomType" -> b.roomType(null);
      case "listingTypes" -> b.listingTypes(null);
      case "furnished" -> b.furnished(null);
      case "bhk" -> b.bhk(null);
      case "moveInDate" -> b.moveInDate(null);
      case "genderPreference" -> b.genderPreference(null);
      case "couplesOk" -> b.couplesOk(null);
      case "amenities" -> b.amenities(null);
      case "lifestyle" -> b.lifestyle(null);
      case "commuteTo", "commuteTo.maxMinutes" -> b.commuteTo(null);
      default -> { /* ALWAYS_HARD and unknown slots are never dropped */ }
    }
    return b.build();
  }
}
```

- [ ] **Step 4: Wire it into `searchHomes`**

`AiResult` gains two components at the end: `boolean nearMiss, String nearMissReason`. Update every construction site (grep `new AiResult(`) — existing sites pass `false, null`.

`Homes` becomes `record Homes(List<AiResult> results, boolean includesNearby, int radiusMinutes, int exactCount, String rescueSummary)`.

In `searchHomes`: retrieve with the original intent as today; then, while the candidate count is below `props.getSearch().getMinResults()` and rungs remain, retrieve that rung's candidates (`retriever.retrieveListings(rung.intent(), rung.radiusMinutes() == null ? props.getSearch().getNearbyRadiusMinutes() : rung.radiusMinutes())`) and add any **new** ids to the map, remembering per id which rung introduced it (0 = exact). Hydrate and score the union exactly once, as today. Then:

- every row from rung > 0 is `nearMiss = true`; its `nearMissReason` is `"~%d min from %s"` (commute minutes and anchor name) when the rung was the wider ring, else `"%s — you asked for %s"` with the rung's label and the value that was dropped;
- sort: exact rows first by score descending, then near-miss rows by rung ascending then score descending;
- `rescueSummary` names the rungs used (`"further out"`, `"budget"`, …), joined with `", "`.

In `search`, when `homes.rescueSummary() != null`, append to the note:

```java
      String rescue =
          "Only %d exact %s — added %d nearby option%s (%s)."
              .formatted(
                  homes.exactCount(),
                  homes.exactCount() == 1 ? "match" : "matches",
                  homes.results().size() - homes.exactCount(),
                  homes.results().size() - homes.exactCount() == 1 ? "" : "s",
                  homes.rescueSummary());
```

Saved-search alerts never reach `searchHomes`, so they are unaffected; assert that in the integration test.

- [ ] **Step 5: Integration test** (append to `SearchPipelineIntegrationTest`)

```java
  @Test
  void anOverTightSearchIsToppedUpWithMarkedNearMisses() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(9000)
            .originalQuery("flat in colaba under 9k")
            .build();

    SearchDtos.AiSearchResponse res = pipeline.search(intent, null, "test", UUID.randomUUID());

    assertThat(res.homes()).isNotEmpty();
    assertThat(res.homes().stream().filter(SearchDtos.AiResult::nearMiss)).isNotEmpty();
    assertThat(res.note()).contains("nearby option");
    // every exact match sorts above every near miss
    int firstNearMiss = -1;
    for (int i = 0; i < res.homes().size(); i++) {
      if (res.homes().get(i).nearMiss() && firstNearMiss < 0) {
        firstNearMiss = i;
      } else if (!res.homes().get(i).nearMiss()) {
        assertThat(firstNearMiss).as("an exact match appeared after a near miss").isLessThan(0);
      }
    }
    assertThat(res.homes().stream().filter(SearchDtos.AiResult::nearMiss))
        .allSatisfy(r -> assertThat(r.nearMissReason()).isNotBlank());
  }
```

(Colaba is the most expensive seed locality at a ₹40,000 rent band, so a ₹9,000 cap there is reliably empty — check the seed before relying on it and pick another over-tight combination if this one already returns results.)

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest=ThinResultRescueTest` → 7 pass.
Run: `./mvnw -q test -Dtest=SearchPipelineIntegrationTest,LocationWideningIntegrationTest` (Docker) → 8 + 4 pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/RescueLadder.java backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/main/java/com/flatmaite/search/SearchDtos.java backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java backend/src/main/resources/application.yml backend/src/test/java/com/flatmaite/search/ThinResultRescueTest.java backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java
git commit -m "$(cat <<'EOF'
Top up a thin page with nearby and near-miss options instead of waiting for a click

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Frontend, docs, full verification

**Files:**
- Modify: `frontend/src/lib/ai-client.ts`, `frontend/src/components/search/AiMatchCard.tsx` (or wherever `AiResult` is rendered — grep for `matchReasons`)
- Modify: `README.md`

- [ ] **Step 1: Mirror the new fields**

In `ai-client.ts`: `SearchIntent` gains `confidence?: Record<string, number> | null;`, `AiResult` gains `nearMiss?: boolean | null; nearMissReason?: string | null;`. Add above `chipsFromIntent`:

```ts
const HARD_THRESHOLD = 0.75;
const ALWAYS_HARD = new Set(["excludeLocations", "verifiedOnly"]);

/** A slot the reader inferred rather than read: it ranks results, it does not filter them. */
export function isSoft(intent: SearchIntent, slot: string): boolean {
  if (ALWAYS_HARD.has(slot)) return false;
  return (intent.confidence?.[slot] ?? 1) < HARD_THRESHOLD;
}
```

`IntentChip` gains `soft?: boolean`. Each chip passes its slot name through `isSoft` and, when soft, prefixes its `value` with `≈ ` and sets `soft: true` — chip key → slot: `budget`→`budgetMax`, `deposit`→`maxDeposit`, `room`→`roomType`, `bhk`→`bhk`, `furnished`→`furnished`, `movein`→`moveInDate`, `gender`→`genderPreference`, `commute`→`commuteTo`, location chips→`locations`. The `verified` chip is never soft.

- [ ] **Step 2: Render the distinction**

Where chips are rendered, a `soft` chip gets a muted style and `title="A preference, not a filter — say it outright to require it"`. Where results are rendered, a card with `nearMiss` gets a small badge reading the `nearMissReason`. Keep both changes minimal and consistent with the existing class vocabulary — read the component first.

- [ ] **Step 3: README**

In the environment-variable table, after `SEARCH_NEARBY_RADIUS_MINUTES`:

```markdown
| `SEARCH_MIN_RESULTS` | `6` | Below this many homes, nearby and near-miss options are added automatically |
| `SEARCH_RESCUE_RADIUS_MINUTES` | `45` | The wider ring the top-up reaches for before it drops any filter |
```

And under the AI/search section, one paragraph: what confidence gating does (stated filters, inferred preferences, `≈` chips), that exclusions and verified-only are never softened, and that a thin page is topped up with marked near misses.

- [ ] **Step 4: Full verification**

Run `./mvnw verify` from `backend/` (Docker up) — every class green. Expected new/changed counts: `SearchIntentConfidenceTest` 6, `IntentGroundingTest` 23, `SearchPipelineConfidenceTest` 6, `HybridRetrieverGatingTest` 10, `ThinResultRescueTest` 7, `MatchScorerTest` 17, `MockIntentLlmTest` 6, `OpenAiLlmsPromptTest` 6, `AiSearchControllerTest` 2, `SearchPipelineIntegrationTest` 8; **`IntentGoldenTest` 2 and every other WS1–WS3 class unchanged**. Report the summary `Tests run:` line and `BUILD SUCCESS`.

Run `npx tsc --noEmit` from `frontend/` — clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/ai-client.ts frontend/src/components README.md
git commit -m "$(cat <<'EOF'
Show which constraints are preferences and which results are near misses

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```
