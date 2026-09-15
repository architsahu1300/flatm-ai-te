package com.flatmaite.search;

import com.flatmaite.ai.EmbeddingProvider;
import com.flatmaite.ai.VectorStoreWriter;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate retrieval: hard SQL filters first, then two rankings inside the filtered set —
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
  public record Retrieval(double score, boolean semanticHit, boolean lexicalHit) {
    /** Defensive value for a candidate no ranking produced; should not occur in practice. */
    public static final Retrieval NONE = new Retrieval(0, false, false);
  }

  public record Candidate(UUID id, UUID localityId, Double lat, Double lng, Retrieval retrieval) {}

  private static final int VECTOR_LIMIT = 100;
  private static final int FTS_LIMIT = 50;
  private static final Pattern STRUCTURED_TOKEN = Pattern.compile("^\\d+k?$");
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final int MAX_LEXICAL_TOKENS = 24;

  private final NamedParameterJdbcTemplate jdbc;
  private final EmbeddingProvider embeddingProvider;
  private final CommuteEstimator commuteEstimator;
  private final LocalityResolver localityResolver;
  private final FlatmaiteProperties props;

  /** Row attributes carried through fusion, keyed by id while the rankings are collected. */
  private record Row(UUID localityId, Double lat, Double lng) {}

  @Transactional(readOnly = true)
  public List<Candidate> retrieveListings(SearchIntent intent) {
    return retrieveListings(intent, props.getSearch().getNearbyRadiusMinutes());
  }

  @Transactional(readOnly = true)
  public List<Candidate> retrieveListings(SearchIntent intent, Integer radiusMinutes) {
    ListingFilters filters = toFiltersWithRadius(intent, radiusMinutes);
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
    // Gating trades a filter for a ranking preference — but scoreFlatmate has no location
    // component, so a soft locality here would be deleted rather than demoted. Where there is
    // nothing to rank with, every slot stays a filter.
    List<UUID> localityIds = admittedLocalityIds(intent.toBuilder().confidence(null).build());
    if (!localityIds.isEmpty()) {
      where.append(" AND fp.locality_ids && CAST(:locIds AS uuid[])");
      params.put("locIds", localityIds.toArray(UUID[]::new));
    }
    List<UUID> excludedIds = excludedLocalityIds(intent);
    if (!excludedIds.isEmpty()) {
      where.append(" AND NOT (fp.locality_ids && CAST(:exclIds AS uuid[]))");
      params.put("exclIds", excludedIds.toArray(UUID[]::new));
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

  /**
   * Embedding-provider outages must not take search down — the vector ranking degrades to
   * newest-first, the lexical ranking still fuses, and every candidate keeps a relevance score
   * (with semanticHit = false).
   */
  private float[] safeEmbed(String text) {
    try {
      return embeddingProvider.embed(text);
    } catch (Exception e) {
      log.warn("Query embedding unavailable, degrading to filter/FTS retrieval: {}", e.getMessage());
      return null;
    }
  }

  /** Browse-time filters: the widened admission. */
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
    ListingFilters.ListingFiltersBuilder b =
        ListingFilters.builder()
            // admittedLocalityIds gates its two rings itself; an empty list is "no locality filter"
            .localityIds(admittedLocalityIds(intent, radiusMinutes))
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

  /** Home localities the user named — every id of an ambiguous alias — minus exclusions. */
  public List<UUID> requestedLocalityIds(SearchIntent intent) {
    List<UUID> ids = new ArrayList<>();
    if (intent.locations() != null) {
      for (SearchIntent.LocationRef ref : intent.locations()) {
        for (UUID id : idsOf(ref)) {
          if (!ids.contains(id)) {
            ids.add(id);
          }
        }
      }
    }
    ids.removeAll(excludedLocalityIds(intent));
    return ids;
  }

  public List<UUID> excludedLocalityIds(SearchIntent intent) {
    List<UUID> ids = new ArrayList<>();
    if (intent.excludeLocations() != null) {
      for (SearchIntent.LocationRef ref : intent.excludeLocations()) {
        for (UUID id : idsOf(ref)) {
          if (!ids.contains(id)) {
            ids.add(id);
          }
        }
      }
    }
    return ids;
  }

  private List<UUID> idsOf(SearchIntent.LocationRef ref) {
    if (ref.localityId() != null) {
      return List.of(ref.localityId());
    }
    return localityResolver.resolve(ref.name()).map(LocalityResolver.Match::localityIds).orElse(List.of());
  }

  private UUID commuteAnchor(SearchIntent intent) {
    if (intent.commuteTo() == null) {
      return null;
    }
    if (intent.commuteTo().localityId() != null) {
      return intent.commuteTo().localityId();
    }
    return localityResolver.resolve(intent.commuteTo().place()).map(m -> m.localityIds().get(0)).orElse(null);
  }

  /** Browse-time admission: requested localities plus their nearby radius. */
  public List<UUID> admittedLocalityIds(SearchIntent intent) {
    return admittedLocalityIds(intent, true);
  }

  /** Boolean convenience: {@code true} widens by the configured radius, {@code false} is strict. */
  public List<UUID> admittedLocalityIds(SearchIntent intent, boolean widenToNearby) {
    return admittedLocalityIds(intent, widenToNearby ? props.getSearch().getNearbyRadiusMinutes() : null);
  }

  /**
   * Requested localities; plus everything within {@code radiusMinutes} of each (when non-null);
   * plus the commute ring when a workplace and a travel time were both stated; minus exclusions.
   * Requested ids come first, then by minutes. Empty list = no locality hard filter.
   *
   * <p>The two rings are gated separately because they are two separate claims by the user, and
   * collapsing them behind one gate fails in both directions: a home area the reader only guessed
   * would delete rows, and a fuzzy home area would throw away a commute the user stated outright.
   * So each ring is admitted only when its own slot is hard.
   *
   * <p>A commute radius nobody stated is skipped rather than defaulted. {@code "room near bkc"}
   * names no travel time, so {@link SearchIntent#DEFAULT_COMMUTE_MINUTES} is our guess, and a guess
   * must not delete a listing 35 minutes from BKC. Nothing is lost but the {@code AND}: the
   * {@code location} score component and the per-result commute label both still measure distance
   * to the anchor, so near-by homes still rank first — they are simply no longer the only ones.
   *
   * <p>Exclusions are removed however the positive side was graded: they are {@code ALWAYS_HARD}.
   */
  public List<UUID> admittedLocalityIds(SearchIntent intent, Integer radiusMinutes) {
    Set<UUID> excluded = new HashSet<>(excludedLocalityIds(intent));
    LinkedHashSet<UUID> admitted = new LinkedHashSet<>();
    Map<UUID, Integer> nearbyMinutes = new HashMap<>();
    if (ConfidenceGate.isHard(intent, "locations")) {
      List<UUID> requested = requestedLocalityIds(intent);
      admitted.addAll(requested);
      if (radiusMinutes != null) {
        for (UUID id : requested) {
          for (CommuteEstimator.Nearby n : commuteEstimator.nearestLocalities(id, radiusMinutes, Integer.MAX_VALUE)) {
            nearbyMinutes.merge(n.localityId(), n.minutes(), Math::min);
          }
        }
      }
    }
    Integer commuteMinutes = enforceableCommuteMinutes(intent);
    UUID anchor = commuteMinutes == null ? null : commuteAnchor(intent);
    if (anchor != null) {
      nearbyMinutes.merge(anchor, 0, Math::min);
      for (CommuteEstimator.Nearby n : commuteEstimator.nearestLocalities(anchor, commuteMinutes, Integer.MAX_VALUE)) {
        nearbyMinutes.merge(n.localityId(), n.minutes(), Math::min);
      }
    }
    nearbyMinutes.entrySet().stream()
        .sorted(Map.Entry.comparingByValue())
        .forEach(e -> admitted.add(e.getKey()));
    admitted.removeAll(excluded);
    return new ArrayList<>(admitted);
  }

  /**
   * How wide the commute ring may be enforced, or null when it may not be enforced at all: the
   * anchor is only inferred, or no radius was ever stated. Both halves must hold — a workplace the
   * user named tells us nothing about how far they are willing to travel.
   */
  private static Integer enforceableCommuteMinutes(SearchIntent intent) {
    if (!ConfidenceGate.isHard(intent, "commuteTo")
        || !ConfidenceGate.isPresent(intent, "commuteTo.maxMinutes")
        || !ConfidenceGate.isHard(intent, "commuteTo.maxMinutes")) {
      return null;
    }
    return intent.commuteTo().maxMinutes();
  }

  /**
   * Turns residual free text into a websearch_to_tsquery expression that matches ANY term and
   * lets ts_rank_cd order by how many match. The default AND semantics need every word of a whole
   * sentence to appear in one title+description — for real queries, that is never.
   *
   * <p>Capped at {@value #MAX_LEXICAL_TOKENS} distinct terms: free text accumulates across a
   * session, and an unbounded OR list matches the whole corpus and turns the ts_rank_cd ORDER BY
   * into a full sort.
   */
  static String lexicalQuery(String freeText) {
    if (freeText == null) {
      return null;
    }
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : NON_WORD.split(freeText.toLowerCase(Locale.ROOT))) {
      if (token.length() >= 3 && !STRUCTURED_TOKEN.matcher(token).matches()) {
        tokens.add(token);
        if (tokens.size() == MAX_LEXICAL_TOKENS) {
          break;
        }
      }
    }
    return tokens.isEmpty() ? null : String.join(" or ", tokens);
  }

  /**
   * Embeds the user's full request — stable across a session — plus whatever residual text was
   * added later and is not already part of it, so refinement nuance reaches the vector side as well
   * as the lexical one. Accumulated residuals begin with the original query; only the remainder is
   * appended.
   */
  static String semanticText(SearchIntent intent) {
    StringBuilder sb = new StringBuilder();
    if (intent.originalQuery() != null) {
      sb.append(intent.originalQuery());
    }
    String freeText = intent.freeText();
    if (freeText != null && !freeText.isBlank()) {
      String base = sb.toString();
      String extra = freeText.startsWith(base) ? freeText.substring(base.length()).trim() : freeText;
      if (!extra.isEmpty() && !base.contains(extra)) {
        if (sb.length() > 0) {
          sb.append(". ");
        }
        sb.append(extra);
      }
    }
    SearchIntent.Lifestyle l = intent.lifestyleOrEmpty();
    if (Boolean.TRUE.equals(l.quiet())) sb.append(". quiet calm peaceful home no parties");
    if ("NO_SMOKERS".equals(l.smoking())) sb.append(". non-smoking household no smokers");
    if ("VEGETARIAN".equals(l.diet())) sb.append(". vegetarian household");
    if ("PET_FRIENDLY".equals(l.pets())) sb.append(". pet friendly");
    return sb.toString();
  }

  static java.time.LocalDate parseMoveIn(String moveInDate) {
    if (moveInDate == null) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(moveInDate);
    } catch (Exception e) {
      return null;
    }
  }
}
