package com.flatmaite.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingQueryServiceWhereTest {

  @Test
  void excludeLocalities_addsANullSafeNotInClause() {
    UUID malad = UUID.randomUUID();
    Map<String, Object> params = new LinkedHashMap<>();

    String where =
        ListingQueryService.buildWhere(
            ListingFilters.builder().excludeLocalityIds(List.of(malad)).build(), params);

    // LEFT JOIN properties: a listing without a property must survive the exclusion
    assertThat(where).contains("(p.locality_id IS NULL OR p.locality_id NOT IN (:excludeLocalityIds))");
    assertThat(params).containsEntry("excludeLocalityIds", List.of(malad));
  }

  @Test
  void noExclusions_noClause() {
    Map<String, Object> params = new LinkedHashMap<>();

    String where = ListingQueryService.buildWhere(ListingFilters.empty(), params);

    assertThat(where).doesNotContain("NOT IN");
    assertThat(params).doesNotContainKey("excludeLocalityIds");
  }

  @Test
  void aRing_isEnforcedByTheBoxAndTheDistanceTogether() {
    Map<String, Object> params = new LinkedHashMap<>();

    String where =
        ListingQueryService.buildWhere(
            ListingFilters.builder()
                .geoRings(List.of(new ListingFilters.GeoRing(19.2045, 72.8519, 5000.0)))
                .build(),
            params);

    // earth_box is what the GiST index on ll_to_earth(lat, lng) can answer, so it does the
    // index-backed cut — but it is a bounding cube, and on its own it would admit a corner
    // property well past the radius the page's heading names. earth_distance trims it to the
    // actual ring. Both, or the figure in the heading is not the figure on the rows.
    assertThat(where).contains("earth_box(ll_to_earth(:geoLat0, :geoLng0), :geoM0) @> ll_to_earth(p.lat, p.lng)");
    assertThat(where).contains("earth_distance(ll_to_earth(:geoLat0, :geoLng0), ll_to_earth(p.lat, p.lng)) <= :geoM0");
    assertThat(params).containsEntry("geoLat0", 19.2045).containsEntry("geoM0", 5000.0);
  }

  @Test
  void aPropertyWithNoCoordinates_survivesEveryRing() {
    Map<String, Object> params = new LinkedHashMap<>();

    String where =
        ListingQueryService.buildWhere(
            ListingFilters.builder()
                .geoRings(List.of(new ListingFilters.GeoRing(19.2045, 72.8519, 5000.0)))
                .build(),
            params);

    // spec §4.9: a missing lat/lng falls back to the locality centroid admission already vetted,
    // and the listing still appears. A blank coordinate column must not delete a home.
    assertThat(where).contains("AND (p.lat IS NULL OR p.lng IS NULL OR ");
  }

  @Test
  void aLocalityTheUserNamed_isExemptFromTheRing() {
    UUID kandivali = UUID.randomUUID();
    Map<String, Object> params = new LinkedHashMap<>();

    String where =
        ListingQueryService.buildWhere(
            ListingFilters.builder()
                .geoRings(List.of(new ListingFilters.GeoRing(19.2045, 72.8519, 5000.0)))
                .geoExemptLocalityIds(List.of(kandivali))
                .build(),
            params);

    // the ring polices localities admitted *by proximity*, which is where a distance is claimed.
    // A home in the locality the user actually typed is in the place they asked for.
    assertThat(where).contains("OR p.locality_id IN (:geoExemptIds)");
    assertThat(params).containsEntry("geoExemptIds", List.of(kandivali));
  }

  @Test
  void noRings_noGeoClause() {
    Map<String, Object> params = new LinkedHashMap<>();

    String where = ListingQueryService.buildWhere(ListingFilters.empty(), params);

    assertThat(where).doesNotContain("earth_box");
    assertThat(params).doesNotContainKey("geoLat0");
  }
}
