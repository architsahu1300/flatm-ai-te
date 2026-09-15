package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.seed.SeedLocalities;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A guess must not delete listings. What the user stated still filters; what the reader inferred
 * only ranks — except for the two promises that are never softened.
 */
class HybridRetrieverGatingTest {

  private static final UUID POWAI = UUID.randomUUID();

  private HybridRetriever retriever;

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(SeedLocalities.entities());
    LocalityResolver resolver = new LocalityResolver(repo);
    resolver.load();
    CommuteEstimator estimator = new CommuteEstimator(repo);
    estimator.reload();
    FlatmaiteProperties props = new FlatmaiteProperties(); // nearbyRadiusMinutes defaults to 25
    // constructor arguments follow HybridRetriever's field declaration order
    retriever = new HybridRetriever(null, null, estimator, resolver, props);
  }

  @Test
  void anInferredSlotIsNotAHardFilter_aStatedOneIs() {
    SearchIntent inferred =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .confidence(Map.of("roomType", 0.5, "furnished", 1.0))
            .build();

    assertThat(ConfidenceGate.isHard(inferred, "roomType")).isFalse();
    assertThat(ConfidenceGate.isHard(inferred, "furnished")).isTrue();
    assertThat(ConfidenceGate.softSlots(inferred)).containsExactly("roomType");
  }

  @Test
  void theThresholdIsInclusive_soAWeakGradeStillFilters() {
    SearchIntent weak =
        SearchIntent.builder().roomType(RoomType.PRIVATE).confidence(Map.of("roomType", 0.75)).build();
    assertThat(ConfidenceGate.isHard(weak, "roomType")).isTrue();
    assertThat(ConfidenceGate.softSlots(weak)).isEmpty();
  }

  @Test
  void exclusionsAndVerifiedOnly_areNeverSoftened() {
    SearchIntent doubted =
        SearchIntent.builder()
            .excludeLocations(List.of(new LocationRef("Powai", POWAI)))
            .verifiedOnly(true)
            .confidence(Map.of("excludeLocations", 0.1, "verifiedOnly", 0.1))
            .build();
    assertThat(ConfidenceGate.isHard(doubted, "excludeLocations")).isTrue();
    assertThat(ConfidenceGate.isHard(doubted, "verifiedOnly")).isTrue();
    assertThat(ConfidenceGate.softSlots(doubted)).isEmpty();
  }

  @Test
  void anUngradedIntentBehavesExactlyAsBefore() {
    SearchIntent old = SearchIntent.builder().roomType(RoomType.ENTIRE).budgetMax(30000).build();
    assertThat(ConfidenceGate.softSlots(old)).isEmpty();
    assertThat(ConfidenceGate.isHard(old, "roomType")).isTrue();
  }

  @Test
  void preferenceSlots_excludeWhatAnExistingComponentAlreadyScores() {
    SearchIntent intent =
        SearchIntent.builder()
            .budgetMax(30000)
            .roomType(RoomType.ENTIRE)
            .locations(List.of(new LocationRef("Powai", POWAI)))
            .confidence(Map.of("budgetMax", 0.5, "roomType", 0.5, "locations", 0.5))
            .build();

    assertThat(ConfidenceGate.softSlots(intent)).containsExactly("locations", "budgetMax", "roomType");
    // budgetFit and location already rank those two; only roomType needs a new component
    assertThat(ConfidenceGate.preferenceSlots(intent)).containsExactly("roomType");
  }

  @Test
  void softSlotsFollowTheCanonicalOrder() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .budgetMax(30000)
            .furnished(Furnishing.SEMI_FURNISHED)
            .confidence(Map.of("roomType", 0.5, "budgetMax", 0.5, "furnished", 0.5))
            .build();
    assertThat(ConfidenceGate.softSlots(intent)).containsExactly("budgetMax", "roomType", "furnished");
  }

  @Test
  void aSoftSlotNeverReachesTheSqlFilters() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .confidence(Map.of("roomType", 0.5, "furnished", 1.0))
            .build();

    ListingFilters filters = retriever.toFilters(intent);

    assertThat(filters.roomType()).isNull();
    assertThat(filters.furnishings()).containsExactly(Furnishing.FULLY_FURNISHED);
  }

  @Test
  void alertsAreGatedTheSameWay() {
    SearchIntent intent =
        SearchIntent.builder().roomType(RoomType.ENTIRE).confidence(Map.of("roomType", 0.5)).build();
    assertThat(retriever.toFilters(intent, false).roomType()).isNull();
  }

  @Test
  void aWiderRescueRadiusAdmitsMoreLocalities() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of(new LocationRef("Goregaon", SeedLocalities.id("Goregaon")))).build();
    int normal = retriever.toFilters(intent).localityIds().size();
    int wide = retriever.toFiltersWithRadius(intent, 45).localityIds().size();
    assertThat(wide).isGreaterThan(normal);
  }

  @Test
  void moveInDateIsRankedByAvailability_soItIsNotAPreferenceSlot() {
    SearchIntent intent =
        SearchIntent.builder().moveInDate("2026-10-01").confidence(Map.of("moveInDate", 0.5)).build();
    assertThat(ConfidenceGate.softSlots(intent)).containsExactly("moveInDate");
    assertThat(ConfidenceGate.preferenceSlots(intent)).isEmpty();
  }

  // ---- the commute ring is gated apart from the home-locality ring ----

  @Test
  void aGuessedCommuteRadiusDoesNotNarrowTheSearch() {
    SearchIntent intent =
        SearchIntent.builder()
            .commuteTo(new CommuteTo("BKC", SeedLocalities.id("BKC"), 30))
            .confidence(Map.of("commuteTo", 1.0, "commuteTo.maxMinutes", 0.5))
            .build();
    assertThat(retriever.toFilters(intent).localityIds()).isEmpty();
  }

  @Test
  void aStatedCommuteRadiusStillNarrowsTheSearch() {
    SearchIntent intent =
        SearchIntent.builder()
            .commuteTo(new CommuteTo("BKC", SeedLocalities.id("BKC"), 20))
            .confidence(Map.of("commuteTo", 1.0, "commuteTo.maxMinutes", 1.0))
            .build();
    assertThat(retriever.toFilters(intent).localityIds()).isNotEmpty();
  }

  @Test
  void aFuzzyHomeAreaDoesNotDiscardAStatedCommuteRing() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", SeedLocalities.id("Powai"))))
            .commuteTo(new CommuteTo("BKC", SeedLocalities.id("BKC"), 20))
            .confidence(Map.of("locations", 0.58, "commuteTo", 1.0, "commuteTo.maxMinutes", 1.0))
            .build();

    List<UUID> admitted = retriever.toFilters(intent).localityIds();

    assertThat(admitted).isNotEmpty();
    assertThat(admitted).contains(SeedLocalities.id("BKC"));
  }

  @Test
  void aSoftCommuteAnchorFiltersNothing() {
    SearchIntent intent =
        SearchIntent.builder()
            .commuteTo(new CommuteTo("BKC", SeedLocalities.id("BKC"), 20))
            .confidence(Map.of("commuteTo", 0.5, "commuteTo.maxMinutes", 1.0))
            .build();
    assertThat(retriever.toFilters(intent).localityIds()).isEmpty();
  }

  @Test
  void anUnstatedCommuteRadiusIsNotPresent_soItIsNeverASoftSlotOfItsOwn() {
    SearchIntent noRadius =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", SeedLocalities.id("BKC"), null)).build();
    assertThat(ConfidenceGate.isPresent(noRadius, "commuteTo")).isTrue();
    assertThat(ConfidenceGate.isPresent(noRadius, "commuteTo.maxMinutes")).isFalse();
    // and with no radius to enforce there is no ring, whatever the anchor's grade
    assertThat(retriever.toFilters(noRadius).localityIds()).isEmpty();
  }

  @Test
  void anExclusionStillApplies_whenTheHomeAreaIsOnlyAGuess() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", SeedLocalities.id("Powai"))))
            .excludeLocations(List.of(new LocationRef("Kurla", SeedLocalities.id("Kurla"))))
            .confidence(Map.of("locations", 0.5, "excludeLocations", 0.1))
            .build();

    ListingFilters filters = retriever.toFilters(intent);

    assertThat(filters.localityIds()).isEmpty(); // the guessed area filters nothing
    assertThat(filters.excludeLocalityIds()).containsExactly(SeedLocalities.id("Kurla")); // the promise holds
  }
}
