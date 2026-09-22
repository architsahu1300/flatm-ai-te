package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.listing.PropertyRepository;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.seed.SeedLocalities;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Where a search is anchored on the map. This is the switch that decides whether the fallback
 * ladder has a distance tier at all: anchored nowhere means no ring to widen, and the user is told
 * that rather than quietly served the whole city. A regression here takes the distance relaxation
 * away silently — the page simply comes back thinner.
 */
class PlacementResolutionTest {

  private LocalityResolver resolver;
  private SearchPipeline pipeline;

  private static final UUID KANDIVALI = SeedLocalities.id("Kandivali");
  private static final UUID BORIVALI = SeedLocalities.id("Borivali");
  private static final UUID BKC = SeedLocalities.id("BKC");

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(SeedLocalities.entities());
    resolver = new LocalityResolver(repo, Mockito.mock(PropertyRepository.class));
    resolver.load();
    FlatmaiteProperties props = new FlatmaiteProperties();
    CommuteEstimator estimator = new CommuteEstimator(repo, props);
    estimator.reload();
    // constructor arguments follow HybridRetriever's field declaration order
    HybridRetriever retriever = new HybridRetriever(null, null, estimator, resolver, props);
    // and SearchPipeline's: only the retriever, the estimator, the resolver and the properties are
    // reachable from placementOf, so the rest stay null rather than pulling in a Spring context
    pipeline =
        new SearchPipeline(
            null, null, retriever, null, null, null, null, null, null, null, estimator, resolver,
            null, null, null, props);
  }

  // ---- LocalityResolver.gazetteerPlacementOf ----

  @Test
  void knownIdsPlaceAtTheirOwnPoint_carryingTheGradeTheSlotEarned() {
    Placement p = resolver.gazetteerPlacementOf(List.of(KANDIVALI), 0.9);

    assertThat(p.placed()).isTrue();
    assertThat(p.source()).isEqualTo(Placement.Source.GAZETTEER);
    assertThat(p.localityIds()).containsExactly(KANDIVALI);
    assertThat(p.lat()).isEqualTo(19.2045);
    assertThat(p.lng()).isEqualTo(72.8519);
    assertThat(p.confidence()).isEqualTo(0.9);
  }

  @Test
  void severalIdsPlaceAtTheirCentroid() {
    Placement p = resolver.gazetteerPlacementOf(List.of(KANDIVALI, BORIVALI), 1.0);

    assertThat(p.localityIds()).containsExactly(KANDIVALI, BORIVALI);
    assertThat(p.lat()).isCloseTo((19.2045 + 19.2307) / 2, within(1e-9));
    assertThat(p.lng()).isCloseTo((72.8519 + 72.8567) / 2, within(1e-9));
  }

  @Test
  void aNullListIsNoPlaceAtAll() {
    assertThat(resolver.gazetteerPlacementOf(null, 1.0).placed()).isFalse();
    assertThat(resolver.gazetteerPlacementOf(null, 1.0)).isEqualTo(Placement.none());
  }

  @Test
  void anEmptyListIsNoPlaceAtAll() {
    assertThat(resolver.gazetteerPlacementOf(List.of(), 1.0)).isEqualTo(Placement.none());
  }

  @Test
  void idsTheGazetteerDoesNotKnowAreNotAnAnchor() {
    Placement p = resolver.gazetteerPlacementOf(List.of(UUID.randomUUID(), UUID.randomUUID()), 1.0);

    // an id we cannot place is honestly no place, never a point invented for it
    assertThat(p).isEqualTo(Placement.none());
  }

  @Test
  void anUnknownIdIsDroppedRatherThanDraggingTheCentroidSomewhereFalse() {
    Placement p = resolver.gazetteerPlacementOf(Arrays.asList(KANDIVALI, UUID.randomUUID()), 1.0);

    assertThat(p.localityIds()).containsExactly(KANDIVALI);
    assertThat(p.lat()).isEqualTo(19.2045);
  }

  @Test
  void aNullIdInTheListIsSkipped_ratherThanThrowing() {
    Placement p = resolver.gazetteerPlacementOf(Arrays.asList(null, KANDIVALI, null), 1.0);

    assertThat(p.localityIds()).containsExactly(KANDIVALI);
  }

  @Test
  void aListOfNothingButNulls_isNoPlaceAtAll() {
    assertThat(resolver.gazetteerPlacementOf(Arrays.asList((UUID) null, null), 1.0))
        .isEqualTo(Placement.none());
  }

  // There is deliberately no test for the `centroid == null` branch: Locality.lat/lng are
  // primitive doubles, so every id the resolver knows has a point, and centroidOf can only come
  // back null for a set containing no known id — which the empty-`known` return above already
  // covers. The guard is kept solely to mirror the identical one in toPlacement.

  // ---- SearchPipeline.placementOf ----

  @Test
  void aStatedHomeAreaAnchorsTheSearch() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of(new LocationRef("Kandivali", KANDIVALI))).build();

    Placement p = pipeline.placementOf(intent);

    assertThat(p.placed()).isTrue();
    assertThat(p.localityIds()).containsExactly(KANDIVALI);
    assertThat(p.confidence()).isEqualTo(1.0);
  }

  @Test
  void aGuessedHomeAreaAnchorsNothing_becauseItIsNotFilteringEither() {
    SearchIntent guessed =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Kandivali", KANDIVALI)))
            .confidence(Map.of("locations", 0.5))
            .build();

    // a soft `locations` puts no locality in the WHERE clause, so there is no ring around it to
    // widen and no honest "within ~N km" to say about the rows
    assertThat(pipeline.placementOf(guessed)).isEqualTo(Placement.none());
  }

  @Test
  void theHomeAreasGradeIsCarriedOntoThePlacement() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Kandivali", KANDIVALI)))
            .confidence(Map.of("locations", 0.8))
            .build();

    assertThat(pipeline.placementOf(intent).confidence()).isEqualTo(0.8);
  }

  @Test
  void aStatedCommuteAnchorsTheSearch_whenNoHomeAreaDoes() {
    SearchIntent intent =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", BKC, 30)).build();

    Placement p = pipeline.placementOf(intent);

    assertThat(p.placed()).isTrue();
    assertThat(p.localityIds()).containsExactly(BKC);
    assertThat(p.lat()).isEqualTo(19.0653);
  }

  @Test
  void aHomeAreaWins_whenBothAHomeAreaAndACommuteAreStated() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Kandivali", KANDIVALI)))
            .commuteTo(new CommuteTo("BKC", BKC, 30))
            .build();

    assertThat(pipeline.placementOf(intent).localityIds()).containsExactly(KANDIVALI);
  }

  @Test
  void aCommuteWithNoLocalityIdAnchorsNothing() {
    SearchIntent intent = SearchIntent.builder().commuteTo(new CommuteTo("Ulwe", null, 30)).build();

    assertThat(pipeline.placementOf(intent)).isEqualTo(Placement.none());
  }

  @Test
  void aGuessedCommuteAnchorsNothing() {
    SearchIntent intent =
        SearchIntent.builder()
            .commuteTo(new CommuteTo("BKC", BKC, 30))
            .confidence(Map.of("commuteTo", 0.5))
            .build();

    assertThat(pipeline.placementOf(intent)).isEqualTo(Placement.none());
  }

  @Test
  void aNameNothingCouldPlaceFallsThroughToTheCommute_ratherThanStoppingAtTheHomeArea() {
    // an unresolvable ref leaves no locality id behind, so the home-area branch places nothing and
    // the stated workplace is still an anchor
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Ulwe", null)))
            .commuteTo(new CommuteTo("BKC", BKC, 30))
            .build();

    assertThat(pipeline.placementOf(intent).localityIds()).containsExactly(BKC);
  }

  @Test
  void aSearchWithNoPlaceAtAllIsAnchoredNowhere() {
    assertThat(pipeline.placementOf(SearchIntent.builder().budgetMax(15000).build()))
        .isEqualTo(Placement.none());
  }

  @Test
  void anExcludedHomeAreaIsNotAnAnchor() {
    // the exclusion removes the only requested id, so nothing is left to measure from
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Kandivali", KANDIVALI)))
            .excludeLocations(List.of(new LocationRef("Kandivali", KANDIVALI)))
            .build();

    assertThat(pipeline.placementOf(intent)).isEqualTo(Placement.none());
  }

  @Test
  void anUnanchoredSearchGetsNoDistanceTier() {
    // the whole point of the switch: placement decides whether the ladder can widen
    SearchIntent intent = SearchIntent.builder().budgetMax(15000).build();

    assertThat(RescueLadder.tiers(intent, pipeline.placementOf(intent), new FlatmaiteProperties.Search()))
        .extracting(RescueLadder.Tier::tier)
        .containsExactly(RescueLadder.SearchTier.EXACT, RescueLadder.SearchTier.OVER_BUDGET);
  }
}
