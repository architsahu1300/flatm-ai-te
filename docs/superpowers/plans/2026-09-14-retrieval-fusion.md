# Retrieval Fusion & Relevance Scoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `HybridRetriever` a real hybrid retriever — two ranked lists (vector, full-text) fused with Reciprocal Rank Fusion — and give `MatchScorer` an always-present relevance component so missing evidence can never raise a score.

**Architecture:** A pure `RankFusion` class merges ordered id lists with RRF (k=60) and normalizes so the top candidate is 1.0. `HybridRetriever` collects a vector ranking and a `ts_rank_cd`-ordered lexical ranking (OR-joined terms via `websearch_to_tsquery`), fuses them, and attaches a `Retrieval(score, semanticHit, lexicalHit)` to every `Candidate`. `MatchScorer` replaces its optional `semantic` component with an always-on `relevance` component fed by that score. `freeText` is restored to its documented meaning (residual keywords) and the embedding query switches to `originalQuery`, so refinements no longer embed the word "cheaper".

**Tech Stack:** Java 17 · Spring Boot 3.5 · `NamedParameterJdbcTemplate` · PostgreSQL 16 + pgvector (`<=>` cosine, HNSW) · Postgres FTS (`tsvector`, `websearch_to_tsquery`, `ts_rank_cd`) · JUnit 5 + AssertJ + Mockito · Testcontainers (`pgvector/pgvector:pg16`)

**Spec:** `docs/superpowers/specs/2026-09-14-retrieval-fusion-design.md`

## Global Constraints

- Java 17; Maven wrapper — run every command from `backend/`.
- **No schema change, no Flyway migration.** `search_tsv` stays title + description.
- **No change to `SearchIntent` fields**, no LLM prompt text change, no frontend change.
- RRF constant `K = 60`. Retrieval limits stay `VECTOR_LIMIT = 100`, `FTS_LIMIT = 50`.
- Relevance component weights: **0.15** for listings, **0.20** for flatmates (the existing `semantic` weights, unchanged).
- Detail strings must be exactly as specified in Task 2 (the explainer LLM is only allowed to cite these).
- Tests that need Docker (Testcontainers) are `HybridRetrieverIntegrationTest` and `SearchPipelineIntegrationTest`. All other tests are pure and run without Docker.
- Commit messages: short imperative subject in the repo's existing style, ending with the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Work on branch `retrieval-fusion` (already created; the spec is its first commit).

**Why this order:** the scorer must stop skipping the relevance component *before* the lexical query starts returning rows. Task 2 fixes the scorer while the lexical query is still the near-dead AND query; Task 3 then makes the lexical query real. Do not reorder.

---

### Task 1: `RankFusion` — pure RRF over ordered id lists

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/RankFusion.java`
- Test: `backend/src/test/java/com/flatmaite/search/RankFusionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static List<RankFusion.Fused> RankFusion.fuse(List<List<UUID>> rankings)` and `public record RankFusion.Fused(UUID id, double rrf, double normalized)`. Output is descending by `rrf`; `normalized = rrf / top rrf` so the first element is `1.0`; ties keep first-appearance order across the rankings in the order given. Empty input → empty list.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/flatmaite/search/RankFusionTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankFusionTest {

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();
  private final UUID d = UUID.randomUUID();

  @Test
  void appearingInBothLists_beatsHighPlacementInOne() {
    // vector says A, B, C — lexical says C, A, D
    List<RankFusion.Fused> fused =
        RankFusion.fuse(List.of(List.of(a, b, c), List.of(c, a, d)));

    assertThat(fused).extracting(RankFusion.Fused::id).containsExactly(a, c, b, d);
    // k = 60: A = 1/61 + 1/62, C = 1/63 + 1/61, B = 1/62 (vector only), D = 1/63 (lexical only)
    assertThat(fused.get(0).rrf()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-12));
    assertThat(fused.get(1).rrf()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
    assertThat(fused.get(2).rrf()).isCloseTo(1.0 / 62, within(1e-12));
    assertThat(fused.get(3).rrf()).isCloseTo(1.0 / 63, within(1e-12));
  }

  @Test
  void normalized_topIsOne_andMonotone() {
    List<RankFusion.Fused> fused =
        RankFusion.fuse(List.of(List.of(a, b, c), List.of(c, a, d)));

    assertThat(fused.get(0).normalized()).isEqualTo(1.0);
    for (int i = 1; i < fused.size(); i++) {
      assertThat(fused.get(i).normalized()).isLessThanOrEqualTo(fused.get(i - 1).normalized());
      assertThat(fused.get(i).normalized()).isGreaterThan(0.0);
    }
  }

  @Test
  void singleList_keepsOrder_andDecaysGently() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      ids.add(UUID.randomUUID());
    }

    List<RankFusion.Fused> fused = RankFusion.fuse(List.of(ids));

    assertThat(fused).extracting(RankFusion.Fused::id).containsExactlyElementsOf(ids);
    assertThat(fused.get(0).normalized()).isEqualTo(1.0);
    // rank 100 → (K + 1) / (K + 100) = 61 / 160
    assertThat(fused.get(99).normalized()).isCloseTo(61.0 / 160.0, within(1e-12));
  }

  @Test
  void emptyInput_yieldsEmptyOutput() {
    assertThat(RankFusion.fuse(List.<List<UUID>>of())).isEmpty();
    assertThat(RankFusion.fuse(List.of(List.<UUID>of(), List.<UUID>of()))).isEmpty();
  }

  @Test
  void ties_keepFirstAppearanceOrder() {
    // B is only in list 1 at rank 2; C is only in list 2 at rank 2 → equal RRF; B was seen first
    List<RankFusion.Fused> fused = RankFusion.fuse(List.of(List.of(a, b), List.of(a, c)));

    assertThat(fused).extracting(RankFusion.Fused::id).containsExactly(a, b, c);
    assertThat(fused.get(1).rrf()).isEqualTo(fused.get(2).rrf());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RankFusionTest 2>&1 | tail -30`
Expected: `COMPILATION ERROR` — `cannot find symbol: class RankFusion`.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/flatmaite/search/RankFusion.java`:

```java
package com.flatmaite.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion over ordered id lists. Only positions count, never scores, so a cosine
 * ranking (0–1) and a ts_rank ranking (unbounded) merge without normalizing either. Appearing in
 * both lists beats a high placement in one; k = 60 flattens the top of the curve so agreement
 * between rankings matters more than being #1 in a single one.
 */
public final class RankFusion {

  private RankFusion() {}

  public static final int K = 60;

  /** One fused entry: the raw RRF score and the same score divided by the top score (top = 1.0). */
  public record Fused(UUID id, double rrf, double normalized) {}

  /**
   * @param rankings ordered id lists, best first. Absence from a list contributes nothing.
   * @return descending by RRF; ties keep first-appearance order across the rankings as given.
   */
  public static List<Fused> fuse(List<List<UUID>> rankings) {
    // insertion order = first appearance, which the stable sort below preserves for ties
    Map<UUID, Double> scores = new LinkedHashMap<>();
    for (List<UUID> ranking : rankings) {
      for (int i = 0; i < ranking.size(); i++) {
        scores.merge(ranking.get(i), 1.0 / (K + i + 1), Double::sum);
      }
    }
    if (scores.isEmpty()) {
      return List.of();
    }
    List<Map.Entry<UUID, Double>> ordered = new ArrayList<>(scores.entrySet());
    ordered.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
    double top = ordered.get(0).getValue();
    List<Fused> out = new ArrayList<>(ordered.size());
    for (Map.Entry<UUID, Double> e : ordered) {
      out.add(new Fused(e.getKey(), e.getValue(), e.getValue() / top));
    }
    return out;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RankFusionTest 2>&1 | tail -15`
Expected: `Tests run: 5, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/flatmaite/search/RankFusion.java src/test/java/com/flatmaite/search/RankFusionTest.java
git commit -m "$(cat <<'EOF'
Add RankFusion: reciprocal rank fusion over ordered id lists

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `Retrieval` on every candidate; always-on `relevance` component

This task changes `Candidate`'s shape, makes both retrievers fuse via `RankFusion` (the lexical query is still the old unranked AND query — that changes in Task 3), replaces `MatchScorer`'s optional `semantic` component with an always-present `relevance` component, rewires `SearchPipeline`, and updates `MatchScorerTest`. Everything must land together because `Candidate` is consumed by all of them.

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` (class javadoc through end of `retrieveFlatmates`, lines 20–181; the helpers below `safeEmbed` are untouched)
- Modify: `backend/src/main/java/com/flatmaite/search/MatchScorer.java:30-49` (candidate records), `:122-126` (listing semantic block), `:256-261` (flatmate semantic block), `:397` (concern exclusions)
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java:240` and `:333`
- Test: `backend/src/test/java/com/flatmaite/search/MatchScorerTest.java` (full replacement)

**Interfaces:**
- Consumes: `RankFusion.fuse` / `RankFusion.Fused` from Task 1.
- Produces:
  - `public record HybridRetriever.Retrieval(double score, boolean semanticHit, boolean lexicalHit)`
  - `public record HybridRetriever.Candidate(UUID id, UUID localityId, Double lat, Double lng, Retrieval retrieval)` — `cosineSim` is removed.
  - `MatchScorer.ListingCandidate(..., HybridRetriever.Retrieval retrieval, Integer commuteMinutes, boolean inPreferredLocality)` and `MatchScorer.FlatmateCandidate(..., HybridRetriever.Retrieval retrieval, double locationOverlap, double profileCompleteness)` — the `Double cosineSim` slot becomes `retrieval` at the same position.
  - `MatchScorer` always emits a `Component("relevance", …)`; package-private `static String listingRelevanceDetail(Retrieval)` and `static String flatmateRelevanceDetail(Retrieval)`.

- [ ] **Step 1: Replace `MatchScorerTest` with the version that expects `Retrieval`**

Overwrite `backend/src/test/java/com/flatmaite/search/MatchScorerTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.listing.Listing;
import com.flatmaite.search.HybridRetriever.Retrieval;
import com.flatmaite.search.MatchScorer.ListingCandidate;
import com.flatmaite.search.SearchIntent.Lifestyle;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchScorerTest {

  private static final Retrieval SEMANTIC_TOP = new Retrieval(1.0, true, false);

  private Listing listing(int rent, SocialStyle social, Boolean smoking) {
    Listing l =
        Listing.builder()
            .listerId(UUID.randomUUID())
            .type(ListingType.PRIVATE_ROOM)
            .roomType(RoomType.PRIVATE)
            .title("Room")
            .rentMonthly(rent)
            .availableFrom(LocalDate.now().plusDays(10))
            .householdSocial(social)
            .householdSmoking(smoking)
            .qualityScore(0.8f)
            .build();
    l.setUpdatedAt(Instant.now());
    return l;
  }

  private SearchIntent intent(Integer budgetMax, Boolean quiet, String smoking) {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .budgetMax(budgetMax)
        .locations(List.of(new LocationRef("BKC", UUID.randomUUID())))
        .lifestyle(Lifestyle.builder().quiet(quiet).smoking(smoking).build())
        .build();
  }

  private ListingCandidate candidate(Listing l, boolean preferred, Integer commute, Retrieval retrieval) {
    return new ListingCandidate(
        l, UUID.randomUUID(), "BKC", true, true, true, retrieval, commute, preferred);
  }

  @Test
  void perfectMatch_scoresHigh_withPositiveDetails() {
    SearchIntent intent = intent(25000, true, "NO_SMOKERS");
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            intent, candidate(listing(22000, SocialStyle.QUIET, false), true, null, new Retrieval(0.9, true, false)));

    assertThat(scored.matchScore()).isGreaterThanOrEqualTo(85);
    assertThat(MatchScorer.positiveDetails(scored))
        .anySatisfy(d -> assertThat(d).contains("under your"))
        .anySatisfy(d -> assertThat(d).contains("Quiet household"));
    assertThat(MatchScorer.concernDetails(scored)).isEmpty();
  }

  @Test
  void overBudget_and_partyFlat_scoreLow_withConcerns() {
    SearchIntent intent = intent(20000, true, "NO_SMOKERS");
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            intent,
            candidate(listing(26000, SocialStyle.VERY_SOCIAL, true), false, 55, new Retrieval(0.3, true, false)));

    assertThat(scored.matchScore()).isLessThan(60);
    assertThat(MatchScorer.concernDetails(scored))
        .anySatisfy(d -> assertThat(d).contains("over your"))
        .anySatisfy(d -> assertThat(d).contains("social, lively"));
  }

  @Test
  void weightsRenormalize_whenComponentsMissing() {
    // No budget, no lifestyle, no location in the intent — only always-on components apply
    SearchIntent bare = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).build();
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(bare, candidate(listing(22000, null, null), false, null, SEMANTIC_TOP));

    double weightSum = scored.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    // relevance .15 + verification .10 + quality .10 + freshness .05 — relevance is always on
    assertThat(weightSum).isCloseTo(0.40, within(1e-9));
    // fully verified + good quality + fresh + top relevance should still score high after renormalizing
    assertThat(scored.matchScore()).isGreaterThan(80);
  }

  @Test
  void relevance_alwaysApplies_evenWithNoHits() {
    SearchIntent bare = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).build();
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            bare, candidate(listing(22000, null, null), false, null, new Retrieval(0.5, false, false)));

    MatchScorer.Component relevance = component(scored, "relevance");
    assertThat(relevance.weight()).isEqualTo(0.15);
    assertThat(relevance.score()).isEqualTo(0.5);
    assertThat(relevance.detail()).isEqualTo("Newest listings shown — semantic matching unavailable");
  }

  @Test
  void lexicalOnlyHit_doesNotOutscore_strongerSemanticHit() {
    // The old bug: a candidate with no similarity had the component skipped and its weight
    // redistributed, so it could beat a candidate with real-but-weaker evidence.
    SearchIntent intent = intent(25000, true, "NO_SMOKERS");
    Listing same = listing(22000, SocialStyle.QUIET, false);
    MatchScorer.Scored semantic =
        MatchScorer.scoreListing(intent, candidate(same, true, null, new Retrieval(0.8, true, false)));
    MatchScorer.Scored lexicalOnly =
        MatchScorer.scoreListing(intent, candidate(same, true, null, new Retrieval(0.5, false, true)));

    assertThat(semantic.matchScore()).isGreaterThan(lexicalOnly.matchScore());
    // both carry the component — nothing was skipped or renormalized differently
    double semanticWeights = semantic.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    double lexicalWeights = lexicalOnly.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    assertThat(semanticWeights).isEqualTo(lexicalWeights);
    assertThat(component(lexicalOnly, "relevance").detail())
        .isEqualTo("Mentions the specific things you asked for");
  }

  @Test
  void relevanceDetail_reflectsBothSources() {
    SearchIntent bare = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).build();
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            bare, candidate(listing(22000, null, null), false, null, new Retrieval(1.0, true, true)));

    assertThat(component(scored, "relevance").detail())
        .isEqualTo("Matches your description on both wording and meaning");
    assertThat(MatchScorer.positiveDetails(scored))
        .contains("Matches your description on both wording and meaning");
  }

  @Test
  void suspiciouslyCheap_isPenalized_notRewarded() {
    SearchIntent intent = intent(30000, null, null);
    MatchScorer.Scored cheap =
        MatchScorer.scoreListing(intent, candidate(listing(6000, null, null), true, null, SEMANTIC_TOP));

    MatchScorer.Component budget = component(cheap, "budgetFit");
    assertThat(budget.score()).isEqualTo(0.7);
    assertThat(budget.detail()).contains("unusually low");
  }

  @Test
  void commuteBeyondPreference_reducesLocationScore() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .commuteTo(new SearchIntent.CommuteTo("BKC", UUID.randomUUID(), 30))
            .build();

    MatchScorer.Scored near =
        MatchScorer.scoreListing(intent, candidate(listing(20000, null, null), false, 18, SEMANTIC_TOP));
    MatchScorer.Scored far =
        MatchScorer.scoreListing(intent, candidate(listing(20000, null, null), false, 55, SEMANTIC_TOP));

    double nearLoc = component(near, "location").score();
    double farLoc = component(far, "location").score();
    assertThat(nearLoc).isGreaterThan(farLoc);
    assertThat(component(far, "location").detail()).contains("~55 min");
  }

  @Test
  void flatmateRelevanceDetail_matchesSpec() {
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, true, true)))
        .isEqualTo("Matches your description on both wording and meaning");
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, true, false)))
        .isEqualTo("Their profile matches your description");
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, false, true)))
        .isEqualTo("Their profile mentions what you asked for");
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, false, false)))
        .isEqualTo("Recently active profiles shown");
  }

  private static MatchScorer.Component component(MatchScorer.Scored scored, String name) {
    return scored.breakdown().stream()
        .filter(c -> c.component().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw test -Dtest=MatchScorerTest 2>&1 | tail -30`
Expected: `COMPILATION ERROR` — `cannot find symbol: class Retrieval` (in `HybridRetriever`).

- [ ] **Step 3: Rewrite the top of `HybridRetriever` — records, both retrievers, and the shared `fuse` helper**

In `backend/src/main/java/com/flatmaite/search/HybridRetriever.java`, replace everything from the class javadoc (`/** Candidate retrieval: ...`) through the closing brace of `retrieveFlatmates` (the line before the `safeEmbed` javadoc) with the block below. Leave `safeEmbed`, `toFilters`, `admittedLocalityIds`, `allLocalityIds`, `semanticText` and `parseMoveIn` exactly as they are. Also add `import java.util.HashSet;` and `import java.util.Set;` to the imports.

```java
/**
 * Candidate retrieval: hard SQL filters first (CTE), then two rankings inside the filtered set —
 * vector similarity and full-text — merged with Reciprocal Rank Fusion. Returns ids plus a
 * {@link Retrieval} for every candidate; hydration/scoring happen above.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetriever {

  /**
   * Fused retrieval standing of one candidate. {@code score} is the RRF score normalized so the
   * best candidate is 1.0 — never null, because a candidate exists only if some ranking produced
   * it. The hit flags say which rankings did, so the scorer's detail text stays honest.
   */
  public record Retrieval(double score, boolean semanticHit, boolean lexicalHit) {}

  public record Candidate(UUID id, UUID localityId, Double lat, Double lng, Retrieval retrieval) {}

  private static final int VECTOR_LIMIT = 100;
  private static final int FTS_LIMIT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final EmbeddingProvider embeddingProvider;
  private final CommuteEstimator commuteEstimator;
  private final LocalityResolver localityResolver;

  /** Row attributes carried through fusion, keyed by id while the rankings are collected. */
  private record Row(UUID localityId, Double lat, Double lng) {}

  @Transactional(readOnly = true)
  public List<Candidate> retrieveListings(SearchIntent intent) {
    ListingFilters filters = toFilters(intent);
    Map<String, Object> params = new LinkedHashMap<>();
    String where = ListingQueryService.buildWhere(filters, params);

    float[] queryEmbedding = safeEmbed(semanticText(intent));
    boolean withVector = queryEmbedding != null;
    params.put("vlimit", VECTOR_LIMIT);
    if (withVector) {
      params.put("qvec", VectorStoreWriter.toVectorLiteral(queryEmbedding));
      jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.ef_search = 100");
    }

    String vectorSql =
        """
        WITH filtered AS (
          SELECT l.id, p.locality_id, p.lat, p.lng, l.embedding, l.created_at
          FROM listings l
          LEFT JOIN properties p ON p.id = l.property_id
          WHERE %s
        )
        SELECT id, locality_id, lat, lng, %s AS sim
        FROM filtered
        ORDER BY %s
        LIMIT :vlimit
        """
            .formatted(
                where,
                withVector
                    ? "CASE WHEN embedding IS NOT NULL THEN 1 - (embedding <=> CAST(:qvec AS vector)) END"
                    : "NULL::float8",
                withVector ? "embedding <=> CAST(:qvec AS vector) NULLS LAST" : "created_at DESC");

    Map<UUID, Row> rows = new LinkedHashMap<>();
    List<UUID> vectorRanking = new ArrayList<>();
    Set<UUID> semanticHits = new HashSet<>();
    jdbc.query(
        vectorSql,
        params,
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          rs.getDouble("sim");
          // wasNull() reports the column read immediately before it — keep these two lines adjacent
          boolean hasSim = !rs.wasNull();
          vectorRanking.add(id);
          if (hasSim) {
            semanticHits.add(id);
          }
          rows.putIfAbsent(
              id,
              new Row(
                  rs.getObject("locality_id", UUID.class),
                  (Double) rs.getObject("lat"),
                  (Double) rs.getObject("lng")));
        });

    // Full-text ranking on the residual free text
    List<UUID> lexicalRanking = new ArrayList<>();
    if (intent.freeText() != null && !intent.freeText().isBlank()) {
      Map<String, Object> ftsParams = new LinkedHashMap<>();
      String ftsWhere = ListingQueryService.buildWhere(filters, ftsParams);
      ftsParams.put("query", intent.freeText());
      ftsParams.put("flimit", FTS_LIMIT);
      String ftsSql =
          """
          SELECT l.id, p.locality_id, p.lat, p.lng
          FROM listings l
          LEFT JOIN properties p ON p.id = l.property_id
          WHERE %s AND l.search_tsv @@ websearch_to_tsquery('english', :query)
          LIMIT :flimit
          """
              .formatted(ftsWhere);
      jdbc.query(
          ftsSql,
          ftsParams,
          rs -> {
            UUID id = rs.getObject("id", UUID.class);
            lexicalRanking.add(id);
            rows.putIfAbsent(
                id,
                new Row(
                    rs.getObject("locality_id", UUID.class),
                    (Double) rs.getObject("lat"),
                    (Double) rs.getObject("lng")));
          });
    }
    return fuse(rows, vectorRanking, semanticHits, lexicalRanking);
  }

  @Transactional(readOnly = true)
  public List<Candidate> retrieveFlatmates(SearchIntent intent, UUID excludeUserId) {
    Map<String, Object> params = new LinkedHashMap<>();
    StringBuilder where = new StringBuilder("fp.is_active = true");
    if (excludeUserId != null) {
      where.append(" AND fp.user_id <> :excludeUser");
      params.put("excludeUser", excludeUserId);
    }
    if (intent.budgetMax() != null) {
      // their minimum must be affordable-ish within the searcher's cap
      where.append(" AND (fp.budget_min IS NULL OR fp.budget_min <= :budgetCap)");
      params.put("budgetCap", (int) (intent.budgetMax() * 1.2));
    }
    List<UUID> localityIds = admittedLocalityIds(intent);
    if (!localityIds.isEmpty()) {
      where.append(" AND fp.locality_ids && CAST(:locIds AS uuid[])");
      params.put("locIds", localityIds.toArray(UUID[]::new));
    }
    if (intent.genderPreference() != null && intent.genderPreference() != GenderPreference.ANY) {
      String genderValue = intent.genderPreference() == GenderPreference.FEMALE_ONLY ? "FEMALE" : "MALE";
      where.append(
          " AND EXISTS (SELECT 1 FROM profiles pr WHERE pr.user_id = fp.user_id AND pr.gender = CAST(:gender AS gender))");
      params.put("gender", genderValue);
    }

    float[] queryEmbedding = safeEmbed(semanticText(intent));
    boolean withVector = queryEmbedding != null;
    params.put("vlimit", VECTOR_LIMIT);
    if (withVector) {
      params.put("qvec", VectorStoreWriter.toVectorLiteral(queryEmbedding));
    }

    String sql =
        """
        SELECT fp.id, %s AS sim
        FROM flatmate_profiles fp
        WHERE %s
        ORDER BY %s
        LIMIT :vlimit
        """
            .formatted(
                withVector
                    ? "CASE WHEN fp.embedding IS NOT NULL THEN 1 - (fp.embedding <=> CAST(:qvec AS vector)) END"
                    : "NULL::float8",
                where,
                withVector ? "fp.embedding <=> CAST(:qvec AS vector) NULLS LAST" : "fp.updated_at DESC");

    Map<UUID, Row> rows = new LinkedHashMap<>();
    List<UUID> vectorRanking = new ArrayList<>();
    Set<UUID> semanticHits = new HashSet<>();
    jdbc.query(
        sql,
        params,
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          rs.getDouble("sim");
          boolean hasSim = !rs.wasNull();
          vectorRanking.add(id);
          if (hasSim) {
            semanticHits.add(id);
          }
          rows.putIfAbsent(id, new Row(null, null, null));
        });
    return fuse(rows, vectorRanking, semanticHits, List.of());
  }

  /** Merges the rankings with RRF and attaches each candidate's {@link Retrieval}. */
  private static List<Candidate> fuse(
      Map<UUID, Row> rows, List<UUID> vectorRanking, Set<UUID> semanticHits, List<UUID> lexicalRanking) {
    Set<UUID> lexicalHits = new HashSet<>(lexicalRanking);
    List<Candidate> out = new ArrayList<>();
    for (RankFusion.Fused f : RankFusion.fuse(List.of(vectorRanking, lexicalRanking))) {
      Row r = rows.get(f.id());
      out.add(
          new Candidate(
              f.id(),
              r.localityId(),
              r.lat(),
              r.lng(),
              new Retrieval(f.normalized(), semanticHits.contains(f.id()), lexicalHits.contains(f.id()))));
    }
    return out;
  }
```

Note on `rs.getDouble("sim"); boolean hasSim = !rs.wasNull();` — the old code called `rs.wasNull()` after reading `lng`, so it reported whether *lng* was null, not `sim`. The two lines are now adjacent on purpose.

- [ ] **Step 4: Update `MatchScorer` — candidate records, relevance component, detail helpers, concern exclusions**

In `backend/src/main/java/com/flatmaite/search/MatchScorer.java`:

(a) Replace the two candidate records (lines 30–49) with:

```java
  public record ListingCandidate(
      Listing listing,
      UUID localityId,
      String localityName,
      boolean emailVerified,
      boolean phoneVerified,
      boolean idOrPropertyVerified,
      HybridRetriever.Retrieval retrieval,
      Integer commuteMinutes,
      boolean inPreferredLocality) {}

  public record FlatmateCandidate(
      FlatmateProfile flatmate,
      Profile profile,
      boolean emailVerified,
      boolean phoneVerified,
      boolean idVerified,
      HybridRetriever.Retrieval retrieval,
      double locationOverlap,
      double profileCompleteness) {}
```

(b) Replace the listing semantic block (lines 122–126):

```java
    // semantic similarity (.15)
    if (c.cosineSim() != null) {
      double rescaled = clamp01((c.cosineSim() - 0.15) / 0.6);
      parts.add(new Component("semantic", 0.15, rescaled, "Description matches what you asked for"));
    }
```

with:

```java
    // relevance (.15) — always applies: every candidate came from at least one retrieval ranking,
    // so there is never a missing component whose weight could flow to the others
    parts.add(
        new Component(
            "relevance", 0.15, clamp01(c.retrieval().score()), listingRelevanceDetail(c.retrieval())));
```

(c) Replace the flatmate semantic block (lines 256–261):

```java
    // semantic (.20)
    if (c.cosineSim() != null) {
      parts.add(
          new Component(
              "semantic", 0.20, clamp01((c.cosineSim() - 0.15) / 0.6), "Their profile matches your description"));
    }
```

with:

```java
    // relevance (.20) — always applies, see scoreListing
    parts.add(
        new Component(
            "relevance", 0.20, clamp01(c.retrieval().score()), flatmateRelevanceDetail(c.retrieval())));
```

(d) Add the two detail helpers directly above the `// ------------------------------------------------------------------ shared` comment:

```java
  static String listingRelevanceDetail(HybridRetriever.Retrieval r) {
    if (r.semanticHit() && r.lexicalHit()) {
      return "Matches your description on both wording and meaning";
    }
    if (r.semanticHit()) {
      return "Description matches what you asked for";
    }
    if (r.lexicalHit()) {
      return "Mentions the specific things you asked for";
    }
    return "Newest listings shown — semantic matching unavailable";
  }

  static String flatmateRelevanceDetail(HybridRetriever.Retrieval r) {
    if (r.semanticHit() && r.lexicalHit()) {
      return "Matches your description on both wording and meaning";
    }
    if (r.semanticHit()) {
      return "Their profile matches your description";
    }
    if (r.lexicalHit()) {
      return "Their profile mentions what you asked for";
    }
    return "Recently active profiles shown";
  }
```

(e) In `concernDetails` (line 397) change `Set.of("freshness", "semantic", "completeness")` to `Set.of("freshness", "relevance", "completeness")`.

- [ ] **Step 5: Rewire `SearchPipeline`**

In `backend/src/main/java/com/flatmaite/search/SearchPipeline.java`:

Line 240 — replace `c == null ? null : c.cosineSim(),` with
`c == null ? new HybridRetriever.Retrieval(0, false, false) : c.retrieval(),`

Line 333 — replace `c == null ? null : c.cosineSim(),` with
`c == null ? new HybridRetriever.Retrieval(0, false, false) : c.retrieval(),`

(`HybridRetriever` is in the same package; no import needed.)

- [ ] **Step 6: Run the scorer test and every other pure test**

Run: `./mvnw test -Dtest='MatchScorerTest,RankFusionTest,NewQueryDetectorTest,RentalVocabularyTest,LifestyleCompatibilityTest,CommuteEstimatorNearbyTest' 2>&1 | tail -20`
Expected: all classes report `Failures: 0, Errors: 0`; `MatchScorerTest` runs 9 tests; `BUILD SUCCESS`. (This also proves the whole module compiles, including `SearchPipelineIntegrationTest`.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/flatmaite/search/HybridRetriever.java src/main/java/com/flatmaite/search/MatchScorer.java src/main/java/com/flatmaite/search/SearchPipeline.java src/test/java/com/flatmaite/search/MatchScorerTest.java
git commit -m "$(cat <<'EOF'
Fuse retrieval rankings with RRF; make relevance an always-on score component

Every candidate now carries a Retrieval(score, semanticHit, lexicalHit).
The scorer no longer skips a component when similarity is missing, so
"less evidence" can never raise a total.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Ranked, OR-joined lexical query for listings and flatmates

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` — the lexical block in `retrieveListings`, the tail of `retrieveFlatmates`, new `lexicalQuery()` + two `Pattern` constants, imports
- Test: `backend/src/test/java/com/flatmaite/search/LexicalQueryTest.java` (new)
- Test: `backend/src/test/java/com/flatmaite/search/HybridRetrieverIntegrationTest.java` (new, Testcontainers)

**Interfaces:**
- Consumes: `Retrieval`, `Candidate`, `fuse(...)`, `Row` from Task 2.
- Produces: package-private `static String HybridRetriever.lexicalQuery(String freeText)` — lowercase → split on non-letter/non-digit → keep tokens of length ≥ 3 → drop tokens matching `^\d+k?$` → dedupe preserving order → join with `" or "`; returns `null` when nothing survives.

- [ ] **Step 1: Write the failing unit test for `lexicalQuery`**

Create `backend/src/test/java/com/flatmaite/search/LexicalQueryTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LexicalQueryTest {

  @Test
  void joinsSurvivingTokensWithOr() {
    // "in"/"no" too short, "25k" is a budget token; "under" survives (Postgres drops it as a stop word)
    assertThat(HybridRetriever.lexicalQuery("quiet 1bhk in andheri under 25k no smokers"))
        .isEqualTo("quiet or 1bhk or andheri or under or smokers");
  }

  @Test
  void dropsShortNumericAndBudgetTokens() {
    assertThat(HybridRetriever.lexicalQuery("2 bhk 30000 25k pg")).isEqualTo("bhk");
  }

  @Test
  void dedupesPreservingFirstOrder() {
    assertThat(HybridRetriever.lexicalQuery("balcony room balcony")).isEqualTo("balcony or room");
  }

  @Test
  void nullWhenNothingSurvives() {
    assertThat(HybridRetriever.lexicalQuery(null)).isNull();
    assertThat(HybridRetriever.lexicalQuery("")).isNull();
    assertThat(HybridRetriever.lexicalQuery("a 2 25k")).isNull();
  }

  @Test
  void toleratesPunctuationAndCase() {
    assertThat(HybridRetriever.lexicalQuery("Sea-facing!! \"Terrace\", (near) metro:"))
        .isEqualTo("sea or facing or terrace or near or metro");
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=LexicalQueryTest 2>&1 | tail -30`
Expected: `COMPILATION ERROR` — `cannot find symbol: method lexicalQuery(String)`.

- [ ] **Step 3: Add `lexicalQuery()` and switch both retrievers to the ranked OR query**

In `backend/src/main/java/com/flatmaite/search/HybridRetriever.java`:

(a) Add imports: `import java.util.LinkedHashSet;`, `import java.util.Locale;`, `import java.util.regex.Pattern;`.

(b) Add two constants next to `VECTOR_LIMIT` / `FTS_LIMIT`:

```java
  private static final Pattern STRUCTURED_TOKEN = Pattern.compile("^\\d+k?$");
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
```

(c) In `retrieveListings`, replace from the `// Full-text ranking on the residual free text` comment line through the `return fuse(rows, vectorRanking, semanticHits, lexicalRanking);` line (inclusive) with:

```java
    // Full-text ranking on the residual free text: ANY term matches, ts_rank_cd orders by how many
    List<UUID> lexicalRanking = new ArrayList<>();
    String lexQuery = lexicalQuery(intent.freeText());
    if (lexQuery != null) {
      Map<String, Object> lexParams = new LinkedHashMap<>();
      String lexWhere = ListingQueryService.buildWhere(filters, lexParams);
      lexParams.put("lexQuery", lexQuery);
      lexParams.put("flimit", FTS_LIMIT);
      String lexicalSql =
          """
          SELECT l.id, p.locality_id, p.lat, p.lng
          FROM listings l
          LEFT JOIN properties p ON p.id = l.property_id
          CROSS JOIN websearch_to_tsquery('english', :lexQuery) AS q
          WHERE %s AND l.search_tsv @@ q
          ORDER BY ts_rank_cd(l.search_tsv, q) DESC, l.id
          LIMIT :flimit
          """
              .formatted(lexWhere);
      jdbc.query(
          lexicalSql,
          lexParams,
          rs -> {
            UUID id = rs.getObject("id", UUID.class);
            lexicalRanking.add(id);
            rows.putIfAbsent(
                id,
                new Row(
                    rs.getObject("locality_id", UUID.class),
                    (Double) rs.getObject("lat"),
                    (Double) rs.getObject("lng")));
          });
    }
    return fuse(rows, vectorRanking, semanticHits, lexicalRanking);
```

(d) In `retrieveFlatmates`, replace the final line `return fuse(rows, vectorRanking, semanticHits, List.of());` with:

```java
    List<UUID> lexicalRanking = new ArrayList<>();
    String lexQuery = lexicalQuery(intent.freeText());
    if (lexQuery != null) {
      params.put("lexQuery", lexQuery);
      params.put("flimit", FTS_LIMIT);
      String lexicalSql =
          """
          SELECT fp.id
          FROM flatmate_profiles fp
          CROSS JOIN websearch_to_tsquery('english', :lexQuery) AS q
          WHERE %s AND fp.search_tsv @@ q
          ORDER BY ts_rank_cd(fp.search_tsv, q) DESC, fp.id
          LIMIT :flimit
          """
              .formatted(where);
      jdbc.query(
          lexicalSql,
          params,
          rs -> {
            UUID id = rs.getObject("id", UUID.class);
            lexicalRanking.add(id);
            rows.putIfAbsent(id, new Row(null, null, null));
          });
    }
    return fuse(rows, vectorRanking, semanticHits, lexicalRanking);
```

(e) Add the tokenizer directly above `static String semanticText(SearchIntent intent)`:

```java
  /**
   * Turns residual free text into a websearch_to_tsquery expression that matches ANY term and
   * lets ts_rank_cd order by how many match. The default AND semantics need every word of a whole
   * sentence to appear in one title+description — for real queries, that is never.
   */
  static String lexicalQuery(String freeText) {
    if (freeText == null) {
      return null;
    }
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : NON_WORD.split(freeText.toLowerCase(Locale.ROOT))) {
      if (token.length() >= 3 && !STRUCTURED_TOKEN.matcher(token).matches()) {
        tokens.add(token);
      }
    }
    return tokens.isEmpty() ? null : String.join(" or ", tokens);
  }
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `./mvnw test -Dtest=LexicalQueryTest 2>&1 | tail -15`
Expected: `Tests run: 5, Failures: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Write the integration test (requires Docker)**

Create `backend/src/test/java/com/flatmaite/search/HybridRetrieverIntegrationTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.flatmate.FlatmateProfile;
import com.flatmaite.flatmate.FlatmateProfileRepository;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.search.HybridRetriever.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Fusion against real Postgres+pgvector with the deterministic seed (Random(42)) and the mock
 * embedding provider. Proves the lexical ranking actually fires and that every candidate carries
 * a normalized retrieval score.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class HybridRetrieverIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired HybridRetriever retriever;
  @Autowired ListingQueryService listingQueryService;
  @Autowired FlatmateProfileRepository flatmateProfiles;

  @Test
  void everyCandidate_carriesNormalizedScore_topIsOne() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .originalQuery("a quiet room")
            .freeText("a quiet room")
            .build();

    List<Candidate> out = retriever.retrieveListings(intent);

    assertThat(out).isNotEmpty();
    assertThat(out.get(0).retrieval().score()).isEqualTo(1.0);
    for (int i = 0; i < out.size(); i++) {
      double score = out.get(i).retrieval().score();
      assertThat(score).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
      if (i > 0) {
        assertThat(score).isLessThanOrEqualTo(out.get(i - 1).retrieval().score());
      }
    }
  }

  @Test
  void listings_lexicalTermOnlyInFurnishedDescriptions_firesAndRanksFirst() {
    // "wardrobe" appears only in FULLY_FURNISHED seed descriptions ("bed, wardrobe and more")
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .originalQuery("room with a wardrobe")
            .freeText("wardrobe")
            .build();

    List<Candidate> out = retriever.retrieveListings(intent);
    List<UUID> lexicalIds =
        out.stream().filter(c -> c.retrieval().lexicalHit()).map(Candidate::id).toList();

    assertThat(lexicalIds).isNotEmpty();
    // fewer active seed listings than VECTOR_LIMIT → every lexical hit is also a vector hit, and
    // RRF places anything in both rankings ahead of anything in one
    assertThat(out.get(0).retrieval().lexicalHit()).isTrue();
    List<Listing> hydrated = listingQueryService.hydrate(lexicalIds);
    assertThat(hydrated)
        .isNotEmpty()
        .allSatisfy(l -> assertThat(l.getFurnishing()).isEqualTo(Furnishing.FULLY_FURNISHED));
  }

  @Test
  void flatmates_lexicalHeadlineTerm_firesAndRanksFirst() {
    // "flatmate" appears only in the headlines of seed profiles that already have a flat
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.FLATMATES)
            .originalQuery("someone who already has a flat and wants a flatmate")
            .freeText("flatmate")
            .build();

    List<Candidate> out = retriever.retrieveFlatmates(intent, null);
    List<UUID> lexicalIds =
        out.stream().filter(c -> c.retrieval().lexicalHit()).map(Candidate::id).toList();

    assertThat(lexicalIds).isNotEmpty();
    assertThat(out.get(0).retrieval().lexicalHit()).isTrue();
    assertThat(out.get(0).retrieval().score()).isEqualTo(1.0);
    List<FlatmateProfile> hydrated = new ArrayList<>();
    flatmateProfiles.findAllById(lexicalIds).forEach(hydrated::add);
    assertThat(hydrated)
        .isNotEmpty()
        .allSatisfy(fp -> assertThat(fp.getHeadline().toLowerCase(Locale.ROOT)).contains("flatmate"));
  }
}
```

- [ ] **Step 6: Run the integration test (Docker must be running)**

Run: `./mvnw test -Dtest=HybridRetrieverIntegrationTest 2>&1 | tail -25`
Expected: `Tests run: 3, Failures: 0, Errors: 0`, `BUILD SUCCESS`. If it fails with a Docker/Testcontainers connection error, start Docker Desktop (`open -a Docker`) and re-run.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/flatmaite/search/HybridRetriever.java src/test/java/com/flatmaite/search/LexicalQueryTest.java src/test/java/com/flatmaite/search/HybridRetrieverIntegrationTest.java
git commit -m "$(cat <<'EOF'
Rank full-text hits with ts_rank_cd and match any residual term

The lexical query used to AND every word of the raw sentence, which
matched nothing for real queries. Flatmate retrieval gains the same
lexical ranking, so its existing tsvector index is finally read.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Restore the `freeText` contract; embed `originalQuery`

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/RefinementHeuristics.java` — remove `.freeText(query)` at lines 24, 27, 35, 39, 42, 45, 52
- Modify: `backend/src/main/java/com/flatmaite/ai/MockLlms.java:46` + new private helper
- Modify: `backend/src/main/java/com/flatmaite/ai/OpenAiLlms.java:116-121` (`finish`)
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` — `semanticText` (currently prefers `freeText`)
- Modify: `backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java` — add one assertion to `refine_cheaper_reducesBudget_andKeepsSession`
- Test (new): `backend/src/test/java/com/flatmaite/search/RefinementHeuristicsTest.java`, `backend/src/test/java/com/flatmaite/ai/MockIntentLlmTest.java`, `backend/src/test/java/com/flatmaite/ai/OpenAiIntentLlmFinishTest.java`, `backend/src/test/java/com/flatmaite/search/SemanticTextTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `OpenAiLlms.OpenAiIntentLlm.finish(SearchIntent extracted, String query, SearchIntent prior)` becomes package-private `static` (was `private static`). Behaviour contract: `freeText` = LLM's value if present; else `query` on the first turn; else `prior.freeText()`.

- [ ] **Step 1: Write the four failing unit tests**

Create `backend/src/test/java/com/flatmaite/search/RefinementHeuristicsTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.SearchTarget;
import org.junit.jupiter.api.Test;

class RefinementHeuristicsTest {

  private final SearchIntent prior =
      SearchIntent.builder()
          .searchTarget(SearchTarget.PROPERTIES)
          .budgetMax(25000)
          .commuteTo(new SearchIntent.CommuteTo("BKC", null, 45))
          .freeText("quiet room near BKC with a balcony")
          .originalQuery("quiet room near BKC with a balcony under 25k")
          .build();

  @Test
  void cheaper_reducesBudget_andKeepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "show me cheaper");

    assertThat(out.budgetMax()).isLessThan(25000);
    assertThat(out.freeText()).isEqualTo(prior.freeText());
    assertThat(out.originalQuery()).isEqualTo(prior.originalQuery());
  }

  @Test
  void onlyVerified_keepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "only verified");

    assertThat(out.verifiedOnly()).isTrue();
    assertThat(out.freeText()).isEqualTo(prior.freeText());
  }

  @Test
  void furnishedOnly_keepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "furnished only");

    assertThat(out.furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
    assertThat(out.freeText()).isEqualTo(prior.freeText());
  }

  @Test
  void closer_tightensCommute_andKeepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "closer to work");

    assertThat(out.commuteTo().maxMinutes()).isEqualTo(36);
    assertThat(out.freeText()).isEqualTo(prior.freeText());
  }

  @Test
  void unmatchedMessage_returnsNull() {
    assertThat(RefinementHeuristics.apply(prior, "with a sea view")).isNull();
  }
}
```

Create `backend/src/test/java/com/flatmaite/ai/MockIntentLlmTest.java`:

```java
package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.LocalityResolver;
import com.flatmaite.search.SearchIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockIntentLlmTest {

  private MockLlms.MockIntentLlm llm() {
    LocalityResolver resolver = mock(LocalityResolver.class);
    when(resolver.scan(anyString())).thenReturn(List.of());
    return new MockLlms.MockIntentLlm(new KeywordIntentParser(resolver));
  }

  @Test
  void firstTurn_freeTextIsTheWholeQuery() {
    SearchIntent first = llm().extract("quiet private room under 25k", null);

    assertThat(first.freeText()).isEqualTo("quiet private room under 25k");
    assertThat(first.originalQuery()).isEqualTo("quiet private room under 25k");
    assertThat(first.budgetMax()).isEqualTo(25000);
  }

  @Test
  void refinement_appendsResidualTerms_andKeepsOriginalQuery() {
    MockLlms.MockIntentLlm llm = llm();
    SearchIntent first = llm.extract("quiet private room under 25k", null);

    SearchIntent refined = llm.extract("with a balcony", first);

    assertThat(refined.freeText()).isEqualTo("quiet private room under 25k with a balcony");
    assertThat(refined.originalQuery()).isEqualTo("quiet private room under 25k");
    assertThat(refined.budgetMax()).isEqualTo(25000);
  }

  @Test
  void refinement_withBlankPriorFreeText_usesTheMessage() {
    SearchIntent prior = SearchIntent.builder().budgetMax(20000).originalQuery("room").build();

    SearchIntent refined = llm().extract("with a balcony", prior);

    assertThat(refined.freeText()).isEqualTo("with a balcony");
  }
}
```

Create `backend/src/test/java/com/flatmaite/ai/OpenAiIntentLlmFinishTest.java`:

```java
package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.search.SearchIntent;
import org.junit.jupiter.api.Test;

class OpenAiIntentLlmFinishTest {

  @Test
  void firstTurn_nullFreeText_fallsBackToQuery() {
    SearchIntent extracted = SearchIntent.builder().budgetMax(25000).build();

    SearchIntent out = OpenAiLlms.OpenAiIntentLlm.finish(extracted, "room under 25k", null);

    assertThat(out.freeText()).isEqualTo("room under 25k");
    assertThat(out.originalQuery()).isEqualTo("room under 25k");
  }

  @Test
  void refinement_nullFreeText_keepsPriorResidual_notTheTweak() {
    SearchIntent prior =
        SearchIntent.builder()
            .freeText("balcony sea view")
            .originalQuery("room with balcony sea view")
            .build();
    SearchIntent extracted = SearchIntent.builder().budgetMax(20000).build();

    SearchIntent out = OpenAiLlms.OpenAiIntentLlm.finish(extracted, "cheaper", prior);

    assertThat(out.freeText()).isEqualTo("balcony sea view");
    assertThat(out.originalQuery()).isEqualTo("room with balcony sea view");
  }

  @Test
  void llmProvidedFreeText_alwaysWins() {
    SearchIntent extracted = SearchIntent.builder().freeText("sea view").build();

    SearchIntent out = OpenAiLlms.OpenAiIntentLlm.finish(extracted, "sea view flat in worli", null);

    assertThat(out.freeText()).isEqualTo("sea view");
  }
}
```

Create `backend/src/test/java/com/flatmaite/search/SemanticTextTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.search.SearchIntent.Lifestyle;
import org.junit.jupiter.api.Test;

class SemanticTextTest {

  @Test
  void originalQueryWins_evenWhenFreeTextDiffers() {
    // the old code embedded freeText, which after a refinement was the single word "cheaper"
    SearchIntent intent =
        SearchIntent.builder().originalQuery("quiet room near BKC").freeText("cheaper").build();

    assertThat(HybridRetriever.semanticText(intent)).isEqualTo("quiet room near BKC");
  }

  @Test
  void lifestyleTags_areAppendedToTheOriginalQuery() {
    SearchIntent intent =
        SearchIntent.builder()
            .originalQuery("quiet room near BKC")
            .lifestyle(Lifestyle.builder().quiet(true).smoking("NO_SMOKERS").build())
            .build();

    assertThat(HybridRetriever.semanticText(intent))
        .startsWith("quiet room near BKC")
        .contains("quiet calm peaceful home")
        .contains("non-smoking household");
  }

  @Test
  void fallsBackToFreeText_whenThereIsNoOriginalQuery() {
    SearchIntent intent = SearchIntent.builder().freeText("balcony").build();

    assertThat(HybridRetriever.semanticText(intent)).isEqualTo("balcony");
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='RefinementHeuristicsTest,MockIntentLlmTest,OpenAiIntentLlmFinishTest,SemanticTextTest' 2>&1 | tail -40`
Expected: `COMPILATION ERROR` for `OpenAiIntentLlmFinishTest` (`finish(...) has private access`). After making `finish` package-private in the next step and re-running, expect assertion failures in the other three: `freeText` equal to `"show me cheaper"` / `"with a balcony"` / `"cheaper"` instead of the expected values.

- [ ] **Step 3: Fix `RefinementHeuristics`**

In `backend/src/main/java/com/flatmaite/search/RefinementHeuristics.java`, delete every `.freeText(query)` call — seven occurrences, at lines 24, 27, 35, 39, 42, 45 and 52. `prior.toBuilder()` already carries `prior.freeText()`. For example line 24 becomes:

```java
      return prior.toBuilder().budgetMax((int) Math.round(max * 0.9 / 500) * 500).build();
```

and the `closer` branch (lines 31–37) becomes:

```java
      return prior.toBuilder()
          .commuteTo(
              new SearchIntent.CommuteTo(
                  prior.commuteTo().place(), prior.commuteTo().localityId(), Math.max(10, (int) (current * 0.8))))
          .build();
```

Update the class javadoc's first sentence to read: `Zero-cost regex pre-pass for the ~10 highest-frequency refinements. A refinement adjusts structured slots only; {@code freeText} (residual keywords) is carried over from the prior intent.`

- [ ] **Step 4: Fix `MockIntentLlm`**

In `backend/src/main/java/com/flatmaite/ai/MockLlms.java` line 46, replace `.freeText(query)` with `.freeText(joinFreeText(prior.freeText(), query))`, and add this helper next to `firstNonNull`:

```java
    /** Residual keywords accumulate across a session; the follow-up's words are appended. */
    private static String joinFreeText(String prior, String query) {
      if (prior == null || prior.isBlank()) {
        return query;
      }
      if (query == null || query.isBlank()) {
        return prior;
      }
      return prior.trim() + " " + query.trim();
    }
```

- [ ] **Step 5: Fix `OpenAiIntentLlm.finish`**

In `backend/src/main/java/com/flatmaite/ai/OpenAiLlms.java`, replace the `finish` method (lines 116–121):

```java
    private static SearchIntent finish(SearchIntent extracted, String query, SearchIntent prior) {
      return extracted.toBuilder()
          .originalQuery(prior != null && prior.originalQuery() != null ? prior.originalQuery() : query)
          .freeText(extracted.freeText() == null ? query : extracted.freeText())
          .build();
    }
```

with:

```java
    /**
     * freeText is the LLM's residual when it gave one; otherwise the whole query on a first turn,
     * or the prior residual on a refinement — never the refinement message itself.
     */
    static SearchIntent finish(SearchIntent extracted, String query, SearchIntent prior) {
      String freeText = extracted.freeText();
      if (freeText == null) {
        freeText = prior == null ? query : prior.freeText();
      }
      return extracted.toBuilder()
          .originalQuery(prior != null && prior.originalQuery() != null ? prior.originalQuery() : query)
          .freeText(freeText)
          .build();
    }
```

- [ ] **Step 6: Switch `semanticText` to `originalQuery`**

In `backend/src/main/java/com/flatmaite/search/HybridRetriever.java`, replace the head of `semanticText`:

```java
  static String semanticText(SearchIntent intent) {
    StringBuilder sb = new StringBuilder();
    if (intent.freeText() != null) {
      sb.append(intent.freeText());
    } else if (intent.originalQuery() != null) {
      sb.append(intent.originalQuery());
    }
```

with:

```java
  /** Embeds the user's full request — stable across a session — not the residual keyword text. */
  static String semanticText(SearchIntent intent) {
    StringBuilder sb = new StringBuilder();
    if (intent.originalQuery() != null) {
      sb.append(intent.originalQuery());
    } else if (intent.freeText() != null) {
      sb.append(intent.freeText());
    }
```

The lifestyle-tag lines that follow are unchanged.

- [ ] **Step 7: Run the four unit tests to verify they pass**

Run: `./mvnw test -Dtest='RefinementHeuristicsTest,MockIntentLlmTest,OpenAiIntentLlmFinishTest,SemanticTextTest' 2>&1 | tail -20`
Expected: 5 + 3 + 3 + 3 tests, `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 8: Add the end-to-end assertion**

In `backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java`, inside `refine_cheaper_reducesBudget_andKeepsSession`, directly after `assertThat((Integer) intent.get("budgetMax")).isLessThan(25000);` add:

```java
    // a budget tweak must not replace the residual free text with the word "cheaper"
    assertThat(intent.get("freeText")).isEqualTo("Find me a room near BKC under 25k, no smokers");
```

- [ ] **Step 9: Run the pipeline integration test (Docker)**

Run: `./mvnw test -Dtest=SearchPipelineIntegrationTest 2>&1 | tail -25`
Expected: `Tests run: 3, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/flatmaite/search/RefinementHeuristics.java src/main/java/com/flatmaite/ai/MockLlms.java src/main/java/com/flatmaite/ai/OpenAiLlms.java src/main/java/com/flatmaite/search/HybridRetriever.java src/test/java/com/flatmaite/search/RefinementHeuristicsTest.java src/test/java/com/flatmaite/ai/MockIntentLlmTest.java src/test/java/com/flatmaite/ai/OpenAiIntentLlmFinishTest.java src/test/java/com/flatmaite/search/SemanticTextTest.java src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java
git commit -m "$(cat <<'EOF'
Keep freeText as residual keywords across refinements; embed originalQuery

"show me cheaper" no longer turns the vector and full-text query into
the word "cheaper".

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Full verification

**Files:** none modified.

- [ ] **Step 1: Run the entire build with Docker running**

Run: `./mvnw verify 2>&1 | tail -40`
Expected: every test class passes — `RankFusionTest` (5), `MatchScorerTest` (9), `LexicalQueryTest` (5), `RefinementHeuristicsTest` (5), `MockIntentLlmTest` (3), `OpenAiIntentLlmFinishTest` (3), `SemanticTextTest` (3), `HybridRetrieverIntegrationTest` (3), `SearchPipelineIntegrationTest` (3), plus the pre-existing `NewQueryDetectorTest`, `RentalVocabularyTest`, `LifestyleCompatibilityTest`, `CommuteEstimatorNearbyTest`, `AuthFlowIntegrationTest` — and `BUILD SUCCESS`.

- [ ] **Step 2: Confirm the working tree is clean and the branch has the expected commits**

Run: `git status --short && git log --oneline main..HEAD`
Expected: no uncommitted changes; five commits on `retrieval-fusion` ahead of `main` (spec + Tasks 1–4).
