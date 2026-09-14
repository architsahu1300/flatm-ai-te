# WS1 — Retrieval fusion & relevance scoring

**Status:** approved design, pre-implementation
**Date:** 2026-09-14
**Scope:** backend `search/` and `ai/` packages. Java + tests only. No schema change, no
`SearchIntent` shape change, no prompt change, no frontend change.

## 1. Problem

`HybridRetriever` is described as hybrid retrieval but only one of its two signals is a
ranking. Concretely:

1. **FTS is unranked and effectively dead.** The lexical query is a boolean `@@` test with no
   `ts_rank`; hits are appended with `putIfAbsent` and `cosineSim = null`. Because
   `websearch_to_tsquery` ANDs every term and receives the whole raw sentence, any realistic
   multi-word query matches zero rows.
2. **Missing evidence is rewarded.** `MatchScorer` skips the `semantic` component when
   `cosineSim` is null and renormalizes its weight onto the remaining components. A candidate
   with *no* similarity evidence therefore out-scores one with weak-but-real similarity.
   The bug is latent only because (1) keeps the FTS path from firing; fixing (1) without (2)
   makes results worse.
3. **Refinements destroy the semantic query.** `RefinementHeuristics` and `MockIntentLlm`
   overwrite `freeText` with the follow-up message, so after "show me cheaper" the embedding
   and FTS query is the single word *cheaper*. `originalQuery` is preserved but unused.
4. **Flatmate retrieval has no lexical side at all**, while `flatmate_profiles.search_tsv` and
   its GIN index are maintained on every write.

## 2. Goals / non-goals

**Goals**
- Two genuine rankings (vector, lexical) fused with Reciprocal Rank Fusion.
- A relevance signal that is present for every candidate, so the scorer never renormalizes
  around a missing component.
- Lexical retrieval that actually fires for natural-language queries.
- Semantic and lexical queries that remain meaningful across a refinement session.
- Symmetric behaviour for homes and flatmates.

**Non-goals (deferred to later workstreams)**
- Widening `search_tsv` to include locality / society names (needs migration + trigger or
  denormalized column) — WS2.
- Any change to `SearchIntent` fields, LLM prompts, `LocalityResolver`, `NewQueryDetector`,
  `KeywordIntentParser` parsing rules, or `EmbeddingTextComposer` (so no re-embedding).
- Reranking with a cross-encoder or LLM.
- Frontend changes. `AiMatchCard` renders `comp.component` verbatim; the breakdown row simply
  reads "Relevance" instead of "Semantic".

## 3. Approach

**Chosen: RRF + rank-normalized relevance component.** Each retriever returns an ordered id
list. `RankFusion` merges them with RRF (`k = 60`) and normalizes by the top score so the best
candidate is `1.0`. That value becomes an always-present `relevance` component in
`MatchScorer`, replacing the optional `semantic` one.

Rejected:
- *Score-level (convex) fusion* — requires normalizing cosine and `ts_rank_cd` (unbounded,
  length-dependent) onto one scale plus an `α` with no eval set to tune it against.
- *Minimal patch (compute cosine for FTS-only hits, keep vector ordering)* — still discards
  the lexical relevance signal; the layer stays half-fixed.

## 4. Design

### 4.1 `search/RankFusion.java` (new, pure)

```java
public final class RankFusion {
  public static final int K = 60;
  public record Fused(UUID id, double rrf, double normalized) {}
  /** rankings: ordered id lists. Output descending by RRF; normalized = rrf / top rrf. */
  public static List<Fused> fuse(List<List<UUID>> rankings)
}
```

- `rrf(id) = Σ_lists 1 / (K + rank)`, rank 1-based; absence from a list contributes 0.
- Ties broken by first appearance across the rankings in the order given (vector first), so
  output is deterministic.
- Empty input → empty output. Single list of *n* → `1.0` at rank 1 decaying to
  `61/(60+n)` at rank *n* (≈0.38 at 100).

### 4.2 `HybridRetriever`

**`Candidate`** becomes `(UUID id, UUID localityId, Double lat, Double lng, Retrieval retrieval)`
with

```java
public record Retrieval(double score, boolean semanticHit, boolean lexicalHit) {}
```

`score` is the normalized RRF (never null). The booleans record which lists contained the
candidate; they drive the scorer's detail text. `cosineSim` leaves the public shape — its only
consumer was `MatchScorer` via `SearchPipeline`.

**Vector query** — SQL unchanged (including the `created_at DESC` fallback when embeddings are
unavailable and `SET LOCAL hnsw.ef_search = 100`). It now also records the ordered id list.
`semanticHit` is true only when the row carried a non-null similarity.

**Lexical query** — becomes a ranking. Same hard-filter `WHERE` fragment as the vector query,
built with its own params map as today:

```sql
SELECT l.id, p.locality_id, p.lat, p.lng
FROM listings l
LEFT JOIN properties p ON p.id = l.property_id
CROSS JOIN websearch_to_tsquery('english', :lexQuery) AS q
WHERE %s AND l.search_tsv @@ q
ORDER BY ts_rank_cd(l.search_tsv, q) DESC, l.id
LIMIT :flimit
```

`:lexQuery` comes from `static String lexicalQuery(String freeText)`:
lowercase → split on `\W+` → keep tokens of length ≥ 3 → drop tokens matching `^\d+k?$`
(budget is a structured slot) → dedupe preserving order → keep at most the first 24 distinct tokens (`MAX_LEXICAL_TOKENS`) → join with `" or "`.
`websearch_to_tsquery` maps `or` to `|`, so a listing matching *any* term qualifies and
`ts_rank_cd` orders by how well it matches. Returns `null` when no token survives; the caller
then skips the lexical query. `websearch_to_tsquery` never throws on malformed input, which
is why it is kept over `to_tsquery`.

**Merge** — `RankFusion.fuse(List.of(vectorIds, lexicalIds))`; candidates are returned in
fused order with `Retrieval` populated. `putIfAbsent` is gone.

**`retrieveFlatmates`** gets the same lexical query against `fp.search_tsv`, reusing its
hand-built `where`, and fuses identically. Limits stay `VECTOR_LIMIT = 100`, `FTS_LIMIT = 50`.

**`semanticText(intent)`** uses `originalQuery`, then appends any part of `freeText` not already
contained in it (so residual nuance added after turn 1 reaches the embedding), then the existing
lifestyle tag suffixes. `toFilters` and `admittedLocalityIds` are unchanged.

### 4.3 `freeText` contract

`SearchIntent.freeText` is *residual nuance not captured by structured fields* (existing
javadoc; the LLM prompt already asks for exactly this). Three implementations violate it:

| Where | Today | After |
|---|---|---|
| `RefinementHeuristics.apply` | `.freeText(query)` | keep `prior.freeText()` |
| `MockIntentLlm.extract`, refinement branch | `.freeText(query)` | `SearchIntent.joinFreeText(prior.freeText(), query)` |
| `OpenAiIntentLlm.finish` when the LLM left `freeText` null | `query` | `prior == null ? query : SearchIntent.joinFreeText(prior.freeText(), query)` |

`SearchIntent.joinFreeText` is blank-safe and caps the result at `MAX_FREE_TEXT_CHARS = 600`, so
accumulated residuals — and client-supplied intents replayed via `/apply` — stay bounded.

`KeywordIntentParser.parse` (first turn, whole query) is unchanged: with OR semantics and
ranking, a full sentence is an acceptable lexical query. `originalQuery` is already preserved
on every path.

Consumers verified unaffected: `saved-screen.tsx` reads `originalQuery ?? freeText`;
session JSON round-trips because the record shape is unchanged; the intent cache key and the
explanation `intentHash` simply miss once.

### 4.4 `MatchScorer`

`ListingCandidate` and `FlatmateCandidate` replace `Double cosineSim` with
`HybridRetriever.Retrieval retrieval`. The optional `semantic` component becomes an
always-present `relevance` component with the existing weights (**.15** listings,
**.20** flatmates):

```java
parts.add(new Component("relevance", 0.15, c.retrieval().score(), relevanceDetail(c.retrieval())));
```

Detail text by source:

| semantic | lexical | Listing | Flatmate |
|---|---|---|---|
| ✓ | ✓ | Matches your description on both wording and meaning | same |
| ✓ | — | Description matches what you asked for | Their profile matches your description |
| — | ✓ | Mentions the specific things you asked for | Their profile mentions what you asked for |
| — | — | *(null — nothing true to claim; never cited)* | *(null — nothing true to claim; never cited)* |

`semanticHit == false` also covers a listing whose own embedding is missing, so no detail text
may imply a provider outage.

`concernDetails` excludes `"relevance"` where it excluded `"semantic"`. `finish()` is
untouched. Because every candidate now carries a score, there is no component to skip and no
weight to redistribute — "less evidence" can no longer raise a total.

### 4.5 `SearchPipeline`

Pass `c.retrieval()` where `c.cosineSim()` was passed (two sites). The `c == null` guard maps
to `new Retrieval(0, false, false)`; it should never fire because hydration is keyed from the
candidate map, and it stays defensive.

### 4.6 Degradation

| Situation | Behaviour |
|---|---|
| Embedding provider down | vector list is `created_at DESC` (existing); fused with lexical; `semanticHit = false` |
| `lexicalQuery()` returns null | lexical query skipped; single-list RRF |
| Both lists empty | no candidates → relaxers, as today |
| Odd free text | `websearch_to_tsquery` degrades to an empty/partial query; never throws |

## 5. Testing

| Test | Kind | Proves |
|---|---|---|
| `RankFusionTest` (new) | pure | A/C/B/D worked example with k=60 arithmetic; single-list normalization; empty input; tie order |
| `LexicalQueryTest` (new) | pure | OR-join; drops `<3`-char, numeric and `\d+k` tokens; dedupes; null when empty; tolerates punctuation |
| `RefinementHeuristicsTest` (new) | pure | "cheaper", "only verified", "furnished only" preserve `prior.freeText()` |
| `MatchScorerTest` (updated) | pure | helper takes `Retrieval`; `weightsRenormalize…` expects weight sum 0.40; **regression**: lexical-only at 0.5 does not out-score semantic-hit at 0.8 on otherwise identical listings; relevance component always present |
| `HybridRetrieverIntegrationTest` (new) | Testcontainers + `seed` profile | every candidate has `score ∈ (0, 1]` with the first at 1.0; a query containing "wardrobe" (present only in `FULLY_FURNISHED` seed descriptions) yields ≥ 1 `lexicalHit` in the top 10 and those hits are all fully furnished — i.e. FTS demonstrably fires |
| `SearchPipelineIntegrationTest` (updated) | existing | after "show me cheaper", `intent.freeText` equals the original query, not "cheaper" |

Seed data is generated with `Random(42)`, so integration assertions are deterministic.
`./mvnw verify` requires Docker for Testcontainers.

## 6. Implementation order

Land the scorer change before the lexical query starts returning rows, so no intermediate
commit exhibits the "missing evidence wins" behaviour:

1. `RankFusion` + tests.
2. `Retrieval` on `Candidate`; `MatchScorer` relevance component; `SearchPipeline` wiring;
   `MatchScorerTest` updates. (Retriever still emits vector-only rankings at this point.)
3. Lexical ranking query + `lexicalQuery()` + fusion in both retrievers; integration test.
4. `freeText` contract fixes + `RefinementHeuristicsTest` + `SearchPipelineIntegrationTest`
   assertion; `semanticText` switch to `originalQuery`.

## 7. Files

**New:** `search/RankFusion.java`, `RankFusionTest`, `LexicalQueryTest`,
`RefinementHeuristicsTest`, `HybridRetrieverIntegrationTest`.

**Modified:** `HybridRetriever`, `MatchScorer`, `SearchPipeline`, `RefinementHeuristics`,
`ai/MockLlms`, `ai/OpenAiLlms`, `MatchScorerTest`, `SearchPipelineIntegrationTest`.

## 8. Post-review amendments (2026-09-14)

The whole-branch review found three spec-level gaps, fixed in the same branch: the neither-hit
relevance detail over-claimed (now null, §4.4); the lexical query and accumulated `freeText` were
unbounded (now capped, §4.2/§4.3); residual text added after the first turn never reached the
embedding (now appended, §4.2). The `originalQuery` reset on an LLM "replace" turn is a
pre-existing arbiter conflict between `NewQueryDetector` and `REFINE_SYSTEM` and is deferred to WS2.
