package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

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
    estimator = new CommuteEstimator(repo);
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
    List<CommuteEstimator.Nearby> nearby = estimator.nearestLocalities(GOREGAON, 60, 10);

    assertThat(nearby).extracting(CommuteEstimator.Nearby::localityId).doesNotContain(GOREGAON);
    // Malad is ~2.4km from Goregaon; Andheri ~6km; Ghatkopar is across the city
    assertThat(nearby.get(0).localityId()).isEqualTo(MALAD);
    assertThat(nearby)
        .extracting(CommuteEstimator.Nearby::minutes)
        .isSorted();
  }

  @Test
  @DisplayName("respects the travel-time ceiling and the result cap")
  void respectsBounds() {
    assertThat(estimator.nearestLocalities(GOREGAON, 20, 10))
        .extracting(CommuteEstimator.Nearby::localityId)
        .containsExactly(MALAD); // only Malad is within ~20 minutes
    assertThat(estimator.nearestLocalities(GOREGAON, 60, 1)).hasSize(1);
  }

  @Test
  @DisplayName("unknown anchor yields no suggestions rather than throwing")
  void unknownAnchorIsEmpty() {
    assertThat(estimator.nearestLocalities(UUID.randomUUID(), 60, 5)).isEmpty();
    assertThat(estimator.nearestLocalities(null, 60, 5)).isEmpty();
  }
}
