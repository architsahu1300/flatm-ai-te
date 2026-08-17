package com.flatmaite.admin;

import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ReportStatus;
import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-mostly admin aggregates via SQL; the few writes are small, audited-by-column updates. */
@Service
@RequiredArgsConstructor
public class AdminService {

  private final NamedParameterJdbcTemplate jdbc;

  public Map<String, Object> stats() {
    Map<String, Object> counts =
        jdbc.queryForMap(
            """
            SELECT
              (SELECT count(*) FROM users WHERE deleted_at IS NULL)                              AS users,
              (SELECT count(*) FROM users WHERE is_suspended)                                    AS suspended_users,
              (SELECT count(*) FROM listings WHERE status = 'ACTIVE' AND deleted_at IS NULL)     AS active_listings,
              (SELECT count(*) FROM listings WHERE scam_risk_score >= 0.5 AND status = 'ACTIVE') AS suspicious_listings,
              (SELECT count(*) FROM flatmate_profiles WHERE is_active)                           AS flatmate_cards,
              (SELECT count(*) FROM conversations)                                               AS conversations,
              (SELECT count(*) FROM messages)                                                    AS messages,
              (SELECT count(*) FROM reports WHERE status IN ('OPEN','UNDER_REVIEW'))             AS open_reports,
              (SELECT count(*) FROM verifications WHERE status = 'PENDING')                      AS pending_verifications,
              (SELECT count(*) FROM agreements)                                                  AS agreements,
              (SELECT count(*) FROM agreements WHERE status = 'SIGNED')                          AS signed_agreements,
              (SELECT count(*) FROM saved_searches)                                              AS saved_searches
            """,
            Map.of());

    // core funnel: searches → sessions with results → conversations → accepted → agreements
    Map<String, Object> funnel =
        jdbc.queryForMap(
            """
            SELECT
              (SELECT count(*) FROM ai_usage_log WHERE feature IN ('INTENT_EXTRACTION','REFINEMENT')) AS ai_searches,
              (SELECT count(*) FROM ai_search_sessions)                                               AS search_sessions,
              (SELECT count(*) FROM conversations)                                                    AS contacts,
              (SELECT count(*) FROM conversations WHERE status = 'ACCEPTED')                          AS connections,
              (SELECT count(*) FROM agreements)                                                       AS agreements
            """,
            Map.of());

    Map<String, Object> aiToday =
        jdbc.queryForMap(
            """
            SELECT count(*) AS calls, coalesce(sum(cost_usd),0) AS cost_usd
            FROM ai_usage_log WHERE created_at >= date_trunc('day', now())
            """,
            Map.of());

    return Map.of("counts", counts, "funnel", funnel, "aiToday", aiToday);
  }

  public List<Map<String, Object>> users(String query, int page, int size) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("q", query == null || query.isBlank() ? null : "%" + query.toLowerCase() + "%")
            .addValue("limit", size)
            .addValue("offset", page * size);
    return jdbc.queryForList(
        """
        SELECT u.id, u.name, u.email, u.phone, u.role::text AS role, u.is_suspended,
               u.created_at, u.last_active_at,
               (SELECT count(*) FROM listings l WHERE l.lister_id = u.id AND l.deleted_at IS NULL) AS listing_count,
               (SELECT count(*) FROM reports r WHERE r.reported_user_id = u.id) AS report_count
        FROM users u
        WHERE u.deleted_at IS NULL
          AND (:q::text IS NULL OR lower(u.name) LIKE :q OR lower(u.email) LIKE :q)
        ORDER BY u.created_at DESC
        LIMIT :limit OFFSET :offset
        """,
        params);
  }

  @Transactional
  public void setSuspended(UUID userId, boolean suspended) {
    int updated =
        jdbc.update(
            "UPDATE users SET is_suspended = :s WHERE id = :id AND deleted_at IS NULL",
            new MapSqlParameterSource().addValue("s", suspended).addValue("id", userId));
    if (updated == 0) {
      throw ApiException.notFound("User not found");
    }
  }

  public List<Map<String, Object>> listings(
      ListingStatus status, boolean suspiciousOnly, int page, int size) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("status", status == null ? null : status.name())
            .addValue("susp", suspiciousOnly)
            .addValue("limit", size)
            .addValue("offset", page * size);
    return jdbc.queryForList(
        """
        SELECT l.id, l.title, l.status::text AS status, l.rent_monthly, l.scam_risk_score,
               l.view_count, l.created_at, u.name AS lister_name, u.id AS lister_id,
               loc.name AS locality,
               (SELECT count(*) FROM reports r WHERE r.reported_listing_id = l.id
                  AND r.status IN ('OPEN','UNDER_REVIEW')) AS open_reports
        FROM listings l
        JOIN users u ON u.id = l.lister_id
        LEFT JOIN properties p ON p.id = l.property_id
        LEFT JOIN localities loc ON loc.id = p.locality_id
        WHERE l.deleted_at IS NULL
          AND (:status::text IS NULL OR l.status::text = :status)
          AND (NOT :susp OR l.scam_risk_score >= 0.5)
        ORDER BY l.scam_risk_score DESC, l.created_at DESC
        LIMIT :limit OFFSET :offset
        """,
        params);
  }

  @Transactional
  public void removeListing(UUID listingId) {
    int updated =
        jdbc.update(
            "UPDATE listings SET status = 'REMOVED', deleted_at = now() WHERE id = :id",
            new MapSqlParameterSource().addValue("id", listingId));
    if (updated == 0) {
      throw ApiException.notFound("Listing not found");
    }
  }

  public List<Map<String, Object>> reports(ReportStatus status) {
    return jdbc.queryForList(
        """
        SELECT r.id, r.reason::text AS reason, r.status::text AS status, r.details, r.created_at,
               r.resolution_note, reporter.name AS reporter_name,
               reported.name AS reported_user_name, r.reported_user_id,
               l.title AS reported_listing_title, r.reported_listing_id, l.scam_risk_score
        FROM reports r
        JOIN users reporter ON reporter.id = r.reporter_id
        LEFT JOIN users reported ON reported.id = r.reported_user_id
        LEFT JOIN listings l ON l.id = r.reported_listing_id
        WHERE (:status::text IS NULL OR r.status::text = :status)
        ORDER BY r.created_at DESC
        LIMIT 200
        """,
        new MapSqlParameterSource().addValue("status", status == null ? null : status.name()));
  }

  @Transactional
  public void resolveReport(UUID adminId, UUID reportId, ReportStatus status, String note) {
    if (status == null || status == ReportStatus.OPEN) {
      throw ApiException.badRequest("invalid_status", "Pick UNDER_REVIEW, RESOLVED or DISMISSED");
    }
    int updated =
        jdbc.update(
            """
            UPDATE reports SET status = :status::report_status, admin_id = :admin,
                   resolution_note = :note,
                   resolved_at = CASE WHEN :status IN ('RESOLVED','DISMISSED') THEN now() ELSE resolved_at END
            WHERE id = :id
            """,
            new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("admin", adminId)
                .addValue("note", note)
                .addValue("id", reportId));
    if (updated == 0) {
      throw ApiException.notFound("Report not found");
    }
  }

  public List<Map<String, Object>> verifications(VerificationStatus status) {
    return jdbc.queryForList(
        """
        SELECT v.id, v.type::text AS type, v.status::text AS status, v.provider, v.created_at,
               u.name AS user_name, u.email, v.user_id, v.property_id
        FROM verifications v
        LEFT JOIN users u ON u.id = v.user_id
        WHERE v.status::text = :status
        ORDER BY v.created_at ASC
        LIMIT 200
        """,
        new MapSqlParameterSource().addValue("status", status.name()));
  }

  @Transactional
  public void reviewVerification(UUID adminId, UUID verificationId, boolean approve) {
    Map<String, Object> row;
    try {
      row =
          jdbc.queryForMap(
              "SELECT type::text AS type, user_id, property_id FROM verifications WHERE id = :id AND status = 'PENDING'",
              new MapSqlParameterSource().addValue("id", verificationId));
    } catch (Exception e) {
      throw ApiException.notFound("Pending verification not found");
    }
    jdbc.update(
        """
        UPDATE verifications SET status = :status::verification_status,
               reviewed_by = :admin, reviewed_at = now()
        WHERE id = :id
        """,
        new MapSqlParameterSource()
            .addValue("status", approve ? "VERIFIED" : "REJECTED")
            .addValue("admin", adminId)
            .addValue("id", verificationId));
    // approved property verification flips the hot denormalized flag used by ranking
    if (approve && "PROPERTY".equals(row.get("type")) && row.get("property_id") != null) {
      jdbc.update(
          "UPDATE properties SET is_verified = true WHERE id = :id",
          new MapSqlParameterSource().addValue("id", row.get("property_id")));
    }
  }

  public Map<String, Object> aiUsage() {
    List<Map<String, Object>> byDay =
        jdbc.queryForList(
            """
            SELECT date_trunc('day', created_at)::date AS day, count(*) AS calls,
                   sum(prompt_tokens) AS prompt_tokens, sum(completion_tokens) AS completion_tokens,
                   coalesce(sum(cost_usd),0) AS cost_usd,
                   count(*) FILTER (WHERE cache_hit) AS cache_hits
            FROM ai_usage_log
            WHERE created_at >= now() - interval '14 days'
            GROUP BY 1 ORDER BY 1 DESC
            """,
            Map.of());
    List<Map<String, Object>> byFeature =
        jdbc.queryForList(
            """
            SELECT feature::text AS feature, provider, count(*) AS calls,
                   coalesce(sum(cost_usd),0) AS cost_usd
            FROM ai_usage_log GROUP BY 1, 2 ORDER BY calls DESC
            """,
            Map.of());
    return Map.of("byDay", byDay, "byFeature", byFeature, "generatedAt", Instant.now().toString());
  }
}
