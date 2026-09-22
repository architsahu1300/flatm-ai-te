package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.seed.SeedLocalities;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The per-property half of a radius search (spec §4.1, ruling R14), against the real
 * {@code earthdistance} predicate and the real GiST index.
 *
 * <p>Locality admission is centroid-granular: "within 5 km of Kandivali" admits whole localities
 * whose <em>centre</em> is inside the ring. A property at the far edge of such a locality can be
 * 6.3 km from Kandivali and still be admitted by that test alone — and it then renders "6.3 km
 * from Kandivali" on a card sitting under a heading that says 5 km. These tests move one real seed
 * property out past the ring and require it to disappear, and then blank its coordinates and
 * require it to come back (§4.9): a missing lat/lng degrades to the locality centroid rather than
 * deleting the listing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class GeoRingRetrievalIntegrationTest {

  private static final double RING_KM = 5.0;

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired HybridRetriever retriever;
  @Autowired ListingQueryService listingQueryService;
  @Autowired NamedParameterJdbcTemplate jdbc;

  /** A real seed property inside a ring-admitted locality, and where it started out. */
  private record Victim(UUID listingId, UUID propertyId, double lat, double lng) {}

  private final UUID anchor = SeedLocalities.id("Kandivali");
  private double anchorLat;
  private double anchorLng;
  private Victim victim;
  private Victim secondVictim;
  private List<UUID> baseline;

  private SearchIntent inKandivali() {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .locations(List.of(new SearchIntent.LocationRef("Kandivali", anchor)))
        .originalQuery("room in kandivali")
        .build();
  }

  /** Every active listing the ring admits right now, through the production filter path. */
  private List<UUID> admittedNow() {
    ListingFilters filters = retriever.toFiltersWithRadius(inKandivali(), RING_KM);
    return listingQueryService.findIds(filters, ListingQueryService.Sort.NEWEST, 0, 500).ids();
  }

  @BeforeEach
  void findAVictimInsideTheRing() {
    Map<String, Object> centre =
        jdbc.queryForMap("SELECT lat, lng FROM localities WHERE id = :id", Map.of("id", anchor));
    anchorLat = ((Number) centre.get("lat")).doubleValue();
    anchorLng = ((Number) centre.get("lng")).doubleValue();

    List<UUID> admittedLocalities = retriever.admittedLocalityIds(inKandivali(), RING_KM);
    assertThat(admittedLocalities)
        .as("the 5 km ring around Kandivali must admit neighbours, or there is nothing to police")
        .hasSizeGreaterThan(1);

    // An active listing in a locality admitted *by proximity* (never the anchor itself, which is
    // exempt by design) whose own point is currently inside the ring — so its disappearance below
    // can only be the per-property predicate, never the locality filter it already passes.
    victim =
        jdbc
            .query(
                """
                SELECT l.id AS listing_id, p.id AS property_id, p.lat, p.lng
                FROM listings l
                JOIN properties p ON p.id = l.property_id
                WHERE l.deleted_at IS NULL
                  AND l.status = 'ACTIVE'
                  AND p.locality_id <> :anchor
                  AND p.locality_id IN (:admitted)
                  AND p.lat IS NOT NULL AND p.lng IS NOT NULL
                ORDER BY l.id
                """,
                Map.of("anchor", anchor, "admitted", admittedLocalities),
                (rs, i) ->
                    new Victim(
                        rs.getObject("listing_id", UUID.class),
                        rs.getObject("property_id", UUID.class),
                        rs.getDouble("lat"),
                        rs.getDouble("lng")))
            .stream()
            .filter(v -> CommuteEstimator.haversineKm(v.lat(), v.lng(), anchorLat, anchorLng) <= RING_KM)
            .findFirst()
            .orElse(null);
    assertThat(victim).as("seed must hold an active listing near, but not in, Kandivali").isNotNull();

    baseline = admittedNow();
    assertThat(baseline).as("the victim starts out admitted").contains(victim.listingId());
  }

  /** The seed is shared with every other test in this container — leave it exactly as found. */
  @AfterEach
  void putItBack() {
    restore(victim);
    restore(secondVictim);
    secondVictim = null;
  }

  private void restore(Victim v) {
    if (v != null) {
      moveTo(v.propertyId(), v.lat(), v.lng());
    }
  }

  private void moveTo(UUID propertyId, Double lat, Double lng) {
    Map<String, Object> params = new HashMap<>();
    params.put("lat", lat);
    params.put("lng", lng);
    params.put("id", propertyId);
    jdbc.update("UPDATE properties SET lat = :lat, lng = :lng WHERE id = :id", params);
  }

  @Test
  void aPropertyPastTheRing_isDropped_thoughItsLocalityIsAdmitted() {
    // ~12 km due north of Kandivali: still in the same locality row, so the locality filter still
    // lets it through, and only the per-property ring can catch it.
    double movedLat = anchorLat + 12.0 / 111.0;
    assertThat(CommuteEstimator.haversineKm(movedLat, anchorLng, anchorLat, anchorLng))
        .isGreaterThan(RING_KM);
    moveTo(victim.propertyId(), movedLat, anchorLng);

    List<UUID> after = admittedNow();

    assertThat(after).doesNotContain(victim.listingId());
    // and nothing else moved: the predicate removed exactly the row that left the ring
    assertThat(after)
        .containsExactlyInAnyOrderElementsOf(
            baseline.stream().filter(id -> !id.equals(victim.listingId())).toList());

    // the tier retrieval the page is actually built from agrees — this is not a filter-builder
    // nicety that the AI path routes around
    assertThat(retriever.retrieveListings(inKandivali(), RING_KM))
        .extracting(HybridRetriever.Candidate::id)
        .doesNotContain(victim.listingId());
  }

  @Test
  void aPropertyWithNoCoordinates_stillAppearsViaItsLocalityCentroid() {
    // spec §4.9. Blank the coordinates of the very property the previous test proves the ring can
    // evict, so the only difference between "dropped" and "kept" is the null itself.
    moveTo(victim.propertyId(), null, null);

    List<UUID> after = admittedNow();

    assertThat(after).contains(victim.listingId());
    assertThat(after).containsExactlyInAnyOrderElementsOf(baseline);
  }

  @Test
  void aPropertyInTheNamedLocalityIsNeverEvicted_howeverFarFromItsCentroid() {
    // Kandivali's own listing is admitted because the user typed "Kandivali", not because of a
    // distance we measured — no claim about distance is being made about it, so no ring may take
    // it away. Localities sprawl; a 6 km-wide one must not lose its far half to its own ring.
    secondVictim =
        jdbc.queryForObject(
            """
            SELECT l.id AS listing_id, p.id AS property_id, p.lat, p.lng
            FROM listings l JOIN properties p ON p.id = l.property_id
            WHERE l.deleted_at IS NULL AND l.status = 'ACTIVE' AND p.locality_id = :anchor
              AND p.lat IS NOT NULL AND p.lng IS NOT NULL
            ORDER BY l.id LIMIT 1
            """,
            Map.of("anchor", anchor),
            (rs, i) ->
                new Victim(
                    rs.getObject("listing_id", UUID.class),
                    rs.getObject("property_id", UUID.class),
                    rs.getDouble("lat"),
                    rs.getDouble("lng")));
    assertThat(secondVictim).as("Kandivali holds an active seed listing").isNotNull();
    assertThat(admittedNow()).contains(secondVictim.listingId());

    moveTo(secondVictim.propertyId(), anchorLat + 9.0 / 111.0, anchorLng);

    assertThat(admittedNow()).contains(secondVictim.listingId());
  }
}
