package com.flatmaite.search;

import com.flatmaite.ai.EmbeddingProvider;
import com.flatmaite.ai.VectorStoreWriter;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate retrieval: hard SQL filters first (CTE), then vector ordering inside the filtered set,
 * unioned with full-text matches. Returns ids + cosine similarity; hydration/scoring happen above.
 */
@Service
@RequiredArgsConstructor
public class HybridRetriever {

  public record Candidate(UUID id, UUID localityId, Double lat, Double lng, Double cosineSim) {}

  private static final int VECTOR_LIMIT = 100;
  private static final int FTS_LIMIT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final EmbeddingProvider embeddingProvider;
  private final CommuteEstimator commuteEstimator;
  private final LocalityResolver localityResolver;

  @Transactional(readOnly = true)
  public List<Candidate> retrieveListings(SearchIntent intent) {
    ListingFilters filters = toFilters(intent);
    Map<String, Object> params = new LinkedHashMap<>();
    String where = ListingQueryService.buildWhere(filters, params);

    String semanticText = semanticText(intent);
    float[] queryEmbedding = embeddingProvider.embed(semanticText);
    params.put("qvec", VectorStoreWriter.toVectorLiteral(queryEmbedding));
    params.put("vlimit", VECTOR_LIMIT);

    jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.ef_search = 100");

    String vectorSql =
        """
        WITH filtered AS (
          SELECT l.id, p.locality_id, p.lat, p.lng, l.embedding
          FROM listings l
          LEFT JOIN properties p ON p.id = l.property_id
          WHERE %s
        )
        SELECT id, locality_id, lat, lng,
               CASE WHEN embedding IS NOT NULL
                    THEN 1 - (embedding <=> CAST(:qvec AS vector)) END AS sim
        FROM filtered
        ORDER BY embedding <=> CAST(:qvec AS vector) NULLS LAST
        LIMIT :vlimit
        """
            .formatted(where);

    Map<UUID, Candidate> merged = new LinkedHashMap<>();
    jdbc.query(
        vectorSql,
        params,
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          double sim = rs.getDouble("sim");
          merged.put(
              id,
              new Candidate(
                  id,
                  rs.getObject("locality_id", UUID.class),
                  (Double) rs.getObject("lat"),
                  (Double) rs.getObject("lng"),
                  rs.wasNull() ? null : sim));
        });

    // Full-text union on the residual free text
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
            merged.putIfAbsent(
                id,
                new Candidate(
                    id,
                    rs.getObject("locality_id", UUID.class),
                    (Double) rs.getObject("lat"),
                    (Double) rs.getObject("lng"),
                    null));
          });
    }
    return new ArrayList<>(merged.values());
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

    float[] queryEmbedding = embeddingProvider.embed(semanticText(intent));
    params.put("qvec", VectorStoreWriter.toVectorLiteral(queryEmbedding));
    params.put("vlimit", VECTOR_LIMIT);

    String sql =
        """
        SELECT fp.id,
               CASE WHEN fp.embedding IS NOT NULL
                    THEN 1 - (fp.embedding <=> CAST(:qvec AS vector)) END AS sim
        FROM flatmate_profiles fp
        WHERE %s
        ORDER BY fp.embedding <=> CAST(:qvec AS vector) NULLS LAST
        LIMIT :vlimit
        """
            .formatted(where);

    List<Candidate> out = new ArrayList<>();
    jdbc.query(
        sql,
        params,
        rs -> {
          double sim = rs.getDouble("sim");
          out.add(new Candidate(rs.getObject("id", UUID.class), null, null, null, rs.wasNull() ? null : sim));
        });
    return out;
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
