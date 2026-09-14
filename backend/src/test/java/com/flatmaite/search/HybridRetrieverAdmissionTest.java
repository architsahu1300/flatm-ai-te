package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
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

  private HybridRetriever retriever;

  @BeforeEach
  void setUp() {
    CommuteEstimator estimator = mock(CommuteEstimator.class);
    when(estimator.nearestLocalities(eq(goregaon), eq(25), anyInt()))
        .thenReturn(List.of(new CommuteEstimator.Nearby(ramMandir, 17), new CommuteEstimator.Nearby(malad, 18)));
    when(estimator.nearestLocalities(eq(bkc), eq(20), anyInt()))
        .thenReturn(List.of(new CommuteEstimator.Nearby(bandra, 12)));
    LocalityResolver resolver = mock(LocalityResolver.class);
    FlatmaiteProperties props = new FlatmaiteProperties(); // nearbyRadiusMinutes defaults to 25
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
  void widened_admitsRequestedFirst_thenNearbyByMinutes() {
    assertThat(retriever.admittedLocalityIds(homeIn(goregaon, "Goregaon")))
        .containsExactly(goregaon, ramMandir, malad);
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
  void budgetRange_carriesBothBounds_ceilingKeepsItsHeadroom() {
    SearchIntent intent =
        homeIn(goregaon, "Goregaon").toBuilder().budgetMin(20000).budgetMax(30000).build();

    assertThat(retriever.toFilters(intent).budgetMin()).isEqualTo(20000);
    assertThat(retriever.toFilters(intent).budgetMax()).isEqualTo(33000); // 10% headroom, ceiling only
  }
}
