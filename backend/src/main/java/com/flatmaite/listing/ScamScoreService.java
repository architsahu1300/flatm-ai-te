package com.flatmaite.listing;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic scam-risk heuristic (0..1), stored on the listing and surfaced only to admins.
 * Signals: rent far below the locality/room-type median, a very new lister account, missing
 * verifications, and open scam/fake reports. Advisory — never auto-removes anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScamScoreService {

  private final JdbcTemplate jdbc;

  @Transactional
  public double recompute(UUID listingId) {
    Double median =
        jdbc.query(
                """
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY l2.rent_monthly)
                FROM listings l2
                JOIN properties p2 ON p2.id = l2.property_id
                WHERE l2.status = 'ACTIVE' AND l2.deleted_at IS NULL
                  AND l2.room_type = (SELECT room_type FROM listings WHERE id = ?)
                  AND p2.locality_id = (SELECT p.locality_id FROM listings l
                                        JOIN properties p ON p.id = l.property_id WHERE l.id = ?)
                """,
                rs -> rs.next() ? (Double) rs.getObject(1) : null,
                listingId,
                listingId);

    Double score =
        jdbc.query(
            """
            SELECT l.rent_monthly,
                   EXTRACT(EPOCH FROM (now() - u.created_at)) / 86400.0 AS account_age_days,
                   (u.email_verified_at IS NOT NULL) AS email_ok,
                   (SELECT count(*) FROM verifications v WHERE v.user_id = u.id
                      AND v.type IN ('GOV_ID','SELFIE') AND v.status = 'VERIFIED') AS id_verifs,
                   (SELECT count(*) FROM reports r WHERE r.reported_listing_id = l.id
                      AND r.status IN ('OPEN','UNDER_REVIEW')
                      AND r.reason IN ('SCAM','FAKE_LISTING')) AS open_reports
            FROM listings l JOIN users u ON u.id = l.lister_id
            WHERE l.id = ?
            """,
            rs -> {
              if (!rs.next()) {
                return null;
              }
              double s = 0;
              double age = rs.getDouble("account_age_days");
              if (age < 7) s += 0.20;
              else if (age < 30) s += 0.10;
              if (!rs.getBoolean("email_ok")) s += 0.10;
              if (rs.getInt("id_verifs") == 0) s += 0.10;
              s += Math.min(0.45, rs.getInt("open_reports") * 0.15);
              return s;
            },
            listingId);

    if (score == null) {
      return 0;
    }
    // price signal computed here where the median is in scope
    Integer rent =
        jdbc.queryForObject("SELECT rent_monthly FROM listings WHERE id = ?", Integer.class, listingId);
    double total = score;
    if (median != null && median > 0 && rent != null) {
      double ratio = rent / median;
      if (ratio < 0.35) total += 0.45;
      else if (ratio < 0.5) total += 0.30;
      else if (ratio < 0.65) total += 0.15;
    }
    total = Math.max(0, Math.min(1, total));
    jdbc.update("UPDATE listings SET scam_risk_score = ? WHERE id = ?", (float) total, listingId);
    return total;
  }

  /** Batch recompute for all active listings (admin action / future daily job). */
  @Transactional
  public int recomputeAll() {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM listings WHERE status = 'ACTIVE' AND deleted_at IS NULL", UUID.class);
    ids.forEach(this::recompute);
    log.info("Recomputed scam scores for {} active listings", ids.size());
    return ids.size();
  }
}
