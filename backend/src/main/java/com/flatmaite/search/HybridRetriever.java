package com.flatmaite.search;

import com.flatmaite.ai.EmbeddingProvider;
import com.flatmaite.ai.VectorStoreWriter;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * Embedding-provider outages must not take search down — the ladder degrades to filters + FTS,
   * and MatchScorer renormalizes semanticSim away when sims are null.
   */
  private float[] safeEmbed(String text) {
    try {
      return embeddingProvider.embed(text);
    } catch (Exception e) {
      log.warn("Query embedding unavailable, degrading to filter/FTS retrieval: {}", e.getMessage());
      return null;
    }
  }

  /** Maps intent → shared hard-filter vocabulary (budget headroom ×1.1 — near-misses surface as concerns). */
  public ListingFilters toFilters(SearchIntent intent) {
    SearchIntent.Lifestyle lifestyle = intent.lifestyleOrEmpty();
    return ListingFilters.builder()
        .localityIds(admittedLocalityIds(intent))
        .budgetMax(intent.budgetMax() == null ? null : (int) (intent.budgetMax() * 1.1))
        .maxDeposit(intent.maxDeposit())
        .roomType(intent.roomType())
        .listingTypes(intent.listingTypes())
        .furnishings(intent.furnished() == null ? null : List.of(intent.furnished()))
        .bhkMin(intent.bhk() == null ? null : intent.bhk().min())
        .bhkMax(intent.bhk() == null ? null : intent.bhk().max())
        .moveInBy(parseMoveIn(intent.moveInDate()))
        .genderPref(intent.genderPreference())
        .amenitySlugs(intent.amenities())
        .verifiedOnly(Boolean.TRUE.equals(intent.verifiedOnly()))
        .smokeFreeHousehold("NO_SMOKERS".equals(lifestyle.smoking()))
        .vegHousehold("VEGETARIAN".equals(lifestyle.diet()))
        .couplesAllowed(intent.couplesOk())
        .build();
  }

  /**
   * Preferred localities ∪ commute-radius expansion. Empty list = no locality hard filter (the
   * location signal then only affects scoring).
   */
  public List<UUID> admittedLocalityIds(SearchIntent intent) {
    List<UUID> ids = new ArrayList<>();
    if (intent.locations() != null) {
      for (SearchIntent.LocationRef ref : intent.locations()) {
        UUID id = ref.localityId() != null ? ref.localityId() : localityResolver.resolve(ref.name());
        if (id != null && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    if (intent.commuteTo() != null) {
      UUID anchor =
          intent.commuteTo().localityId() != null
              ? intent.commuteTo().localityId()
              : localityResolver.resolve(intent.commuteTo().place());
      if (anchor != null) {
        int maxMinutes = intent.commuteTo().maxMinutes() == null ? 45 : intent.commuteTo().maxMinutes();
        for (UUID locality : allLocalityIds()) {
          Integer minutes = commuteEstimator.minutesBetween(locality, anchor);
          if (minutes != null && minutes <= maxMinutes && !ids.contains(locality)) {
            ids.add(locality);
          }
        }
      }
    }
    return ids;
  }

  private List<UUID> allLocalityIds() {
    List<UUID> ids = new ArrayList<>();
    jdbc.query("SELECT id FROM localities", Map.of(), rs -> {
      ids.add(rs.getObject("id", UUID.class));
    });
    return ids;
  }

  static String semanticText(SearchIntent intent) {
    StringBuilder sb = new StringBuilder();
    if (intent.freeText() != null) {
      sb.append(intent.freeText());
    } else if (intent.originalQuery() != null) {
      sb.append(intent.originalQuery());
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
