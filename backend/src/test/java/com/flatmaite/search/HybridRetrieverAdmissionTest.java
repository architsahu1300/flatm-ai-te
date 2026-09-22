package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.SearchTarget;
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
