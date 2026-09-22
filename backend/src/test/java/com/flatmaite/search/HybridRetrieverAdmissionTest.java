package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridRetrieverAdmissionTest {

  private final UUID goregaon = UUID.nameUUIDFromBytes("Goregaon".getBytes());
  private final UUID ramMandir = UUID.nameUUIDFromBytes("Ram Mandir".getBytes());
  private final UUID malad = UUID.nameUUIDFromBytes("Malad".getBytes());
  private final UUID bkc = UUID.nameUUIDFromBytes("BKC".getBytes());
  private final UUID bandra = UUID.nameUUIDFromBytes("Bandra".getBytes());
  private final UUID colaba = UUID.nameUUIDFromBytes("Colaba".getBytes());

  private HybridRetriever retriever;

  @BeforeEach
  void setUp() {
    CommuteEstimator estimator = mock(CommuteEstimator.class);
    // The requested-locality ring is admitted at the caller's km argument directly, which defaults
    // to props.getSearch().getNearbyRadiusKm() (5.0).
    when(estimator.nearestLocalities(eq(goregaon), eq(5.0), anyInt()))
        .thenReturn(
            List.of(
                new CommuteEstimator.Nearby(ramMandir, 1.2, 17),
                new CommuteEstimator.Nearby(malad, 1.4, 18)));
    // The commute-anchor ring is admitted at a km budget derived from the stated minutes through
    // Mumbai's default calibration (unknown city here): (20 - 8 overhead) / 60 * 20 kmph / 1.4
    // circuity ≈ 2.857 km.
    when(estimator.nearestLocalities(eq(bkc), doubleThat(km -> Math.abs(km - 2.857142857142857) < 0.0001), anyInt()))
        .thenReturn(List.of(new CommuteEstimator.Nearby(bandra, 0.9, 12)));
    LocalityResolver resolver = mock(LocalityResolver.class);
    // Centroids for the two anchors that get rings drawn around them. Everything else resolves to
    // null, which is how the rest of this class stays a pure admission test: no centroid, no ring.
    when(resolver.pointOf(goregaon)).thenReturn(new double[] {19.1663, 72.8526});
    when(resolver.pointOf(bkc)).thenReturn(new double[] {19.0653, 72.8693});
    FlatmaiteProperties props = new FlatmaiteProperties(); // nearbyRadiusKm defaults to 5.0
    // constructor arguments follow HybridRetriever's field declaration order
    retriever = new HybridRetriever(null, null, estimator, resolver, props);
  }

  private SearchIntent homeIn(UUID id, String name) {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .locations(List.of(new LocationRef(name, id)))
        .build();
  }

  @Test
  void widened_admitsRequestedFirst_thenNearbyByKm() {
    assertThat(retriever.admittedLocalityIds(homeIn(goregaon, "Goregaon")))
        .containsExactly(goregaon, ramMandir, malad);
  }

  @Test
  void aNamedLocalityAdmitsEverythingWithinTheKilometreRing() {
    SearchIntent intent = homeIn(goregaon, "Goregaon");

    List<UUID> admitted = retriever.admittedLocalityIds(intent, 5.0);

    assertThat(admitted).contains(goregaon, malad); // Malad is ~2.4 km away
    assertThat(admitted).doesNotContain(colaba); // Colaba is ~25 km away
  }

  @Test
  void aZeroRadiusAdmitsOnlyTheRequestedLocality() {
    SearchIntent intent = homeIn(goregaon, "Goregaon");

    assertThat(retriever.admittedLocalityIds(intent, 0.0)).containsExactly(goregaon);
  }

  @Test
  void strict_admitsOnlyTheRequestedLocalities() {
    assertThat(retriever.admittedLocalityIds(homeIn(goregaon, "Goregaon"), false)).containsExactly(goregaon);
    assertThat(retriever.toFilters(homeIn(goregaon, "Goregaon"), false).localityIds()).containsExactly(goregaon);
  }

  @Test
  void exclusions_applyInBothModes() {
    SearchIntent intent =
        homeIn(goregaon, "Goregaon").toBuilder()
            .excludeLocations(List.of(new LocationRef("Malad", malad)))
            .build();

    assertThat(retriever.admittedLocalityIds(intent, true)).containsExactly(goregaon, ramMandir);
    assertThat(retriever.admittedLocalityIds(intent, false)).containsExactly(goregaon);
  }

  @Test
  void explicitCommuteRadius_appliesEvenInStrictMode() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .commuteTo(new CommuteTo("BKC", bkc, 20))
            .build();

    assertThat(retriever.admittedLocalityIds(intent, false)).containsExactly(bkc, bandra);
  }

  // ---- which calls draw per-property rings, and which deliberately do not (ruling R14) ----

  private SearchIntent commuteToBkc() {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .commuteTo(new CommuteTo("BKC", bkc, 20))
        .build();
  }

  @Test
  void strictMode_drawsNoRing_evenForAStatedCommuteCap() {
    // The alert path: SavedSearchAlertRunner calls toFilters(intent, false), i.e. radius 0.0. A
    // stored "within 30 minutes of BKC" alert has always matched every property in a locality whose
    // CENTROID is inside the budget. Tightening that to per-property here would silently change
    // what existing alerts fire on — so the strict path emits no geo predicate at all, for any
    // intent, exactly as before this workstream.
    assertThat(retriever.toFilters(commuteToBkc(), false).geoRings()).isEmpty();
    assertThat(retriever.toFiltersWithRadius(commuteToBkc(), 0.0).geoRings()).isEmpty();

    // and the locality-level commute filter is untouched by that: it still applies in strict mode
    assertThat(retriever.toFilters(commuteToBkc(), false).localityIds()).containsExactly(bkc, bandra);
  }

  @Test
  void aWidenedCall_ringsTheCommuteAnchor() {
    List<ListingFilters.GeoRing> rings = retriever.toFiltersWithRadius(commuteToBkc(), 5.0).geoRings();

    // one ring, around BKC, at the same km budget the locality side was admitted at: (20 - 8) / 60
    // × 20 kmph ÷ 1.4 circuity ≈ 2.857 km
    assertThat(rings).hasSize(1);
    assertThat(rings.get(0).lat()).isEqualTo(19.0653);
    assertThat(rings.get(0).meters()).isCloseTo(2857.14, within(1.0));
  }

  @Test
  void aHomeAreaAndAWorkplaceTogether_getARingEach() {
    // The reason the commute anchor is ringed at all. With only the home circle drawn, every
    // locality admitted for being near the office would be evicted by a circle it was never
    // measured against — the office half of the query would quietly stop working.
    SearchIntent intent =
        homeIn(goregaon, "Goregaon").toBuilder().commuteTo(new CommuteTo("BKC", bkc, 20)).build();

    List<ListingFilters.GeoRing> rings = retriever.toFiltersWithRadius(intent, 5.0).geoRings();

    assertThat(rings).hasSize(2);
    assertThat(rings)
        .extracting(ListingFilters.GeoRing::lat)
        .containsExactlyInAnyOrder(19.1663, 19.0653);
    // the rings are OR-ed in the SQL, so a property near either anchor survives
    assertThat(rings).extracting(ListingFilters.GeoRing::meters).anySatisfy(m -> assertThat(m).isEqualTo(5000.0));
  }

  @Test
  void budgetMin_reachesFilters_withNoHeadroom_inBothModes() {
    SearchIntent intent = homeIn(goregaon, "Goregaon").toBuilder().budgetMin(30000).build();

    assertThat(retriever.toFilters(intent).budgetMin()).isEqualTo(30000);
    assertThat(retriever.toFilters(intent, false).budgetMin()).isEqualTo(30000);
  }

  @Test
  void budgetRange_carriesBothBounds_exactly_becauseTheHeadroomIsItsOwnTierNow() {
    SearchIntent intent =
        homeIn(goregaon, "Goregaon").toBuilder().budgetMin(20000).budgetMax(30000).build();

    assertThat(retriever.toFilters(intent).budgetMin()).isEqualTo(20000);
    // the ×1.1 that used to ride on every query is RescueLadder's OVER_BUDGET tier, and only that
    // tier's own block is labelled as over budget — a base query returns only what fits (WS6 §4.5)
    assertThat(retriever.toFilters(intent).budgetMax()).isEqualTo(30000);
  }
}
