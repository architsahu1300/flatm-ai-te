package com.flatmaite.listing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyRepository extends JpaRepository<Property, java.util.UUID> {
  java.util.List<Property> findByOwnerId(java.util.UUID ownerId);

  /**
   * Resolution ladder step 2: place names our own listings already carry, when the gazetteer has
   * no entry for them. {@code city} is nullable — {@code null} reaches every seeded city, matching
   * {@link com.flatmaite.search.CityScope#unset()}. Grouped by locality and ranked by how many
   * matching listings sit there, so an ambiguous name resolves to its most common locality first.
   *
   * <p>{@code needle} is escaped before binding (see {@link #escapeLikePattern}) so a literal
   * {@code %} or {@code _} in a place name — "Sector_15", say — is matched as itself, not as a SQL
   * wildcard; the query's {@code ESCAPE} clause names {@code \} as the escape character to match.
   */
  default List<PlacementRow> findPlacementByPlaceName(String needle, String city) {
    return findPlacementByEscapedPlaceName(escapeLikePattern(needle), city);
  }

  /** Doubles a literal backslash first, then escapes the two LIKE wildcards, {@code %}/{@code _}. */
  private static String escapeLikePattern(String needle) {
    return needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  @Query(
      value =
          """
          SELECT p.locality_id AS localityId, avg(p.lat) AS lat, avg(p.lng) AS lng
          FROM properties p
          JOIN localities l ON l.id = p.locality_id
          WHERE (:city IS NULL OR lower(l.city) = lower(:city))
            AND (lower(p.society_name) LIKE lower(concat('%', :needle, '%')) ESCAPE '\\'
                 OR lower(p.address_line) LIKE lower(concat('%', :needle, '%')) ESCAPE '\\')
          GROUP BY p.locality_id
          ORDER BY count(*) DESC
          LIMIT 3
          """,
      nativeQuery = true)
  List<PlacementRow> findPlacementByEscapedPlaceName(@Param("needle") String needle, @Param("city") String city);

  interface PlacementRow {
    UUID getLocalityId();

    Double getLat();

    Double getLng();
  }
}
