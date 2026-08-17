package com.flatmaite.listing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReferenceController {

  private final LocalityRepository localities;
  private final AmenityRepository amenities;
  private final NamedParameterJdbcTemplate jdbc;

  @GetMapping("/localities")
  public ResponseEntity<Map<String, Object>> localities() {
    return ResponseEntity.ok(Map.of("data", localities.findAll(Sort.by("name"))));
  }

  @GetMapping("/amenities")
  public ResponseEntity<Map<String, Object>> amenities() {
    return ResponseEntity.ok(Map.of("data", amenities.findAll(Sort.by("label"))));
  }

  /** Median rent by room type + active counts for one locality — feeds the SEO landing pages. */
  @GetMapping("/localities/{id}/stats")
  public ResponseEntity<Map<String, Object>> localityStats(@PathVariable UUID id) {
    List<Map<String, Object>> byRoomType =
        jdbc.queryForList(
            """
            SELECT l.room_type::text AS room_type,
                   count(*) AS listings,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY l.rent_monthly)::int AS median_rent
            FROM listings l
            JOIN properties p ON p.id = l.property_id
            WHERE p.locality_id = :id AND l.status = 'ACTIVE' AND l.deleted_at IS NULL
            GROUP BY 1 ORDER BY 2 DESC
            """,
            Map.of("id", id));
    Long flatmates =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM flatmate_profiles fp
            WHERE fp.is_active AND :id = ANY(fp.locality_ids)
            """,
            Map.of("id", id),
            Long.class);
    return ResponseEntity.ok(
        Map.of("data", Map.of("byRoomType", byRoomType, "activeFlatmates", flatmates == null ? 0 : flatmates)));
  }
}
