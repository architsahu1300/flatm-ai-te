package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Real Mumbai centroids: the nearby-area relaxer is only useful if the ordering is trustworthy. */
class CommuteEstimatorNearbyTest {

  private static final UUID GOREGAON = UUID.nameUUIDFromBytes("goregaon".getBytes());
  private static final UUID MALAD = UUID.nameUUIDFromBytes("malad".getBytes());
  private static final UUID ANDHERI = UUID.nameUUIDFromBytes("andheri".getBytes());
  private static final UUID GHATKOPAR = UUID.nameUUIDFromBytes("ghatkopar".getBytes());

  private CommuteEstimator estimator;

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality(GOREGAON, "Goregaon", 19.1663, 72.8526),
                locality(MALAD, "Malad", 19.1875, 72.8489),
                locality(ANDHERI, "Andheri", 19.1197, 72.8468),
                locality(GHATKOPAR, "Ghatkopar", 19.0863, 72.9081)));
    estimator = new CommuteEstimator(repo, new FlatmaiteProperties());
    estimator.loadCentroids();
  }

  private static Locality locality(UUID id, String name, double lat, double lng) {
    Locality l = Locality.builder().name(name).lat(lat).lng(lng).build();
    l.setId(id);
    return l;
  }

  @Test
  @DisplayName("nearest first, anchor excluded")
  void ordersByTravelTime() {
    List<CommuteEstimator.Nearby> nearby = estimator.nearestLocalities(GOREGAON, 60.0, 10);

    assertThat(nearby).extracting(CommuteEstimator.Nearby::localityId).doesNotContain(GOREGAON);
    // Malad is ~2.4km from Goregaon; Andheri ~5.2km; Ghatkopar ~10.6km, across the city
    assertThat(nearby.get(0).localityId()).isEqualTo(MALAD);
    assertThat(nearby)
        .extracting(CommuteEstimator.Nearby::minutes)
        .isSorted();
  }

  @Test
  @DisplayName("respects the distance ceiling and the result cap")
  void respectsBounds() {
    assertThat(estimator.nearestLocalities(GOREGAON, 3.0, 10))
        .extracting(CommuteEstimator.Nearby::localityId)
        .containsExactly(MALAD); // only Malad is within ~3km; Andheri is ~5.2km away
    assertThat(estimator.nearestLocalities(GOREGAON, 60.0, 1)).hasSize(1);
  }

  @Test
  @DisplayName("unknown anchor yields no suggestions rather than throwing")
  void unknownAnchorIsEmpty() {
    assertThat(estimator.nearestLocalities(UUID.randomUUID(), 60.0, 5)).isEmpty();
    assertThat(estimator.nearestLocalities(null, 60.0, 5)).isEmpty();
  }

  @Test
  void reload_picksUpCentroidsAddedAfterStartup() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Locality goregaon = locality(GOREGAON, "Goregaon", 19.1663, 72.8526);
    Locality malad = locality(MALAD, "Malad", 19.1874, 72.8484);
    Mockito.when(repo.findAll()).thenReturn(List.of()).thenReturn(List.of(goregaon, malad));
    CommuteEstimator fresh = new CommuteEstimator(repo, new FlatmaiteProperties());
    fresh.loadCentroids();
    assertThat(fresh.minutesBetween(goregaon.getId(), malad.getId())).isNull();

    fresh.reload();

    assertThat(fresh.minutesBetween(goregaon.getId(), malad.getId())).isNotNull();
  }

  @Test
  void nearbyCarriesTheKilometresTheMinutesWereDerivedFrom() {
    // Goregaon → Malad: ~2.4 km apart by centroid
    List<CommuteEstimator.Nearby> near = estimator.nearestLocalities(GOREGAON, 5.0, 10);

    CommuteEstimator.Nearby malad =
        near.stream().filter(n -> n.localityId().equals(MALAD)).findFirst().orElseThrow();

    assertThat(malad.km()).isBetween(2.0, 5.0);
    // the minute figure is the km figure run through Mumbai's calibration (road-circuity 1.4,
    // 20 km/h, 8 min overhead), not an independent guess
    assertThat(malad.minutes())
        .isEqualTo((int) Math.round(malad.km() * 1.4 / 20.0 * 60 + 8));
  }

  @Test
  void theConversionTableIsPinnedSoTheOverheadCannotDriftUnnoticed() {
    // 2 km and 5 km of straight-line distance, through Mumbai's calibration: circuity is applied
    // inside the minutes conversion, not baked into the km figure.
    assertThat(CommuteEstimator.minutesForKm(2.0, mumbai())).isEqualTo(16);
    assertThat(CommuteEstimator.minutesForKm(5.0, mumbai())).isEqualTo(29);
  }

  @Test
  void anUnknownCityFallsBackToMumbaiRatherThanZero() {
    FlatmaiteProperties.Geo geo = new FlatmaiteProperties.Geo();
    assertThat(geo.calibrationFor("Atlantis")).isEqualTo(geo.calibrationFor("Mumbai"));
  }

  private static FlatmaiteProperties.Calibration mumbai() {
    return new FlatmaiteProperties.Geo().calibrationFor("Mumbai");
  }
}
