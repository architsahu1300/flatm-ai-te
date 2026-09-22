package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.listing.PropertyRepository;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IntentLocalitiesTest {

  private LocalityResolver resolver;
  private PropertyRepository properties;

  private static Locality locality(String name, String... aliases) {
    return localityIn("Mumbai", name, aliases);
  }

  private static Locality localityIn(String city, String name, String... aliases) {
    Locality l = Locality.builder().name(name).city(city).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes());
  }

  /** The unscoped ladder — every seeded city, which is what an UNSET viewer gets (§4.11). */
  private static SearchIntent resolve(SearchIntent intent, LocalityResolver resolver) {
    return IntentLocalities.resolve(intent, resolver, CityScope.unset());
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", "hiranandani"),
                locality("Andheri East", "andheri"),
                locality("Andheri West", "andheri"),
                locality("BKC", "bandra kurla complex", "bandra kurla"),
                localityIn("Bangalore", "Indiranagar")));
    properties = Mockito.mock(PropertyRepository.class);
    resolver = new LocalityResolver(repo, properties);
    resolver.load();
  }

  /**
   * Makes our own Mumbai listings place {@code placeName} at {@code localityId} — resolution ladder
   * step 2, the layer that exists so "Hiranandani" resolves because listings say Hiranandani.
   */
  private void ownData(String placeName, UUID localityId) {
    PropertyRepository.PlacementRow row = Mockito.mock(PropertyRepository.PlacementRow.class);
    Mockito.when(row.getLocalityId()).thenReturn(localityId);
    Mockito.when(row.getLat()).thenReturn(19.12);
    Mockito.when(row.getLng()).thenReturn(72.91);
    List<PropertyRepository.PlacementRow> rows = List.of(row);
    Mockito.when(properties.findPlacementByPlaceName(placeName, "Mumbai")).thenReturn(rows);
  }

  @Test
  void unknownName_movesToUnresolved_andIntoFreeText() {
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Atlantis", null)))
            .freeText("room in atlantis")
            .originalQuery("room in Atlantis")
            .build();

    SearchIntent out = resolve(in, resolver);

    assertThat(out.locations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Atlantis");
    assertThat(out.freeText()).isEqualTo("room in atlantis"); // already mentioned — not appended twice
  }

  @Test
  void unknownName_isAppendedToFreeText_whenAbsent() {
    SearchIntent in =
        SearchIntent.builder().locations(List.of(new LocationRef("Atlantis", null))).freeText("quiet room").build();

    assertThat(resolve(in, resolver).freeText()).isEqualTo("quiet room Atlantis");
  }

  @Test
  void ambiguousAlias_expandsToEveryLocality() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("andheri", null))).build();

    SearchIntent out = resolve(in, resolver);

    assertThat(out.locations()).extracting(LocationRef::localityId).containsExactlyInAnyOrder(id("Andheri East"), id("Andheri West"));
    assertThat(out.locations()).extracting(LocationRef::name).containsExactlyInAnyOrder("Andheri East", "Andheri West");
  }

  @Test
  void alreadyResolvedRef_keepsItsIdAndName() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("My Powai", id("Powai")))).build();

    assertThat(resolve(in, resolver).locations()).containsExactly(new LocationRef("My Powai", id("Powai")));
  }

  @Test
  void commutePlace_isCanonicalised_orMarkedUnresolved() {
    SearchIntent known = SearchIntent.builder().commuteTo(new CommuteTo("bandra kurla", null, 20)).build();
    SearchIntent unknown = SearchIntent.builder().commuteTo(new CommuteTo("Nowhere", null, 20)).build();

    CommuteTo resolved = resolve(known, resolver).commuteTo();
    assertThat(resolved.localityId()).isEqualTo(id("BKC"));
    assertThat(resolved.place()).isEqualTo("BKC");
    assertThat(resolved.maxMinutes()).isEqualTo(20);

    SearchIntent out = resolve(unknown, resolver);
    assertThat(out.commuteTo().localityId()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Nowhere");
  }

  @Test
  void excludedUnknownName_isSurfacedToo() {
    SearchIntent in = SearchIntent.builder().excludeLocations(List.of(new LocationRef("Atlantis", null))).build();

    SearchIntent out = resolve(in, resolver);

    assertThat(out.excludeLocations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Atlantis");
  }

  @Test
  void nothingToResolve_returnsTheSameIntent() {
    SearchIntent in = SearchIntent.builder().budgetMax(20000).build();

    assertThat(resolve(in, resolver)).isSameAs(in);
  }

  @Test
  void aLocalityBothRequestedAndExcluded_exclusionWins() {
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", null)))
            .excludeLocations(List.of(new LocationRef("Powai", null)))
            .build();

    SearchIntent out = resolve(in, resolver);

    assertThat(out.locations()).isNull();
    assertThat(out.excludeLocations()).extracting(LocationRef::localityId).containsExactly(id("Powai"));
  }

  @Test
  void partiallyExcludedRequest_keepsWhatWasNotExcluded() {
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", null), new LocationRef("Andheri East", null)))
            .excludeLocations(List.of(new LocationRef("Powai", null)))
            .build();

    SearchIntent out = resolve(in, resolver);

    assertThat(out.locations()).extracting(LocationRef::localityId).containsExactly(id("Andheri East"));
  }

  @Test
  void unresolvedLocationsOnly_stillAppendsToFreeText() {
    // reachable if a model emits only unresolvedLocations with no locations/exclude/commute set
    SearchIntent in =
        SearchIntent.builder().unresolvedLocations(List.of("Atlantis")).freeText("quiet room").build();

    assertThat(resolve(in, resolver).freeText()).isEqualTo("quiet room Atlantis");
  }

  // ---- the scoped resolution ladder (spec §4.3, ruling R9) ----

  @Test
  void aNameOnlyOurOwnListingsCanPlace_landsInLocations_notInUnresolved() {
    ownData("Oberoi Splendor", id("Powai"));
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Oberoi Splendor", null)))
            .originalQuery("room near Oberoi Splendor")
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.unresolvedLocations()).isNull();
    assertThat(out.locations()).extracting(LocationRef::localityId).containsExactly(id("Powai"));
    assertThat(out.locations()).extracting(LocationRef::name).containsExactly("Powai");
  }

  @Test
  void anOwnDataBinding_arrivesAsAPreference_neverAsAHardFilter() {
    ownData("Oberoi Splendor", id("Powai"));
    SearchIntent in =
        SearchIntent.builder().locations(List.of(new LocationRef("Oberoi Splendor", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    // the fixed 0.5 the ladder's own-data step assigns, carried onto the slot it bound
    assertThat(out.confidenceOf("locations")).isEqualTo(0.5);
    assertThat(ConfidenceGate.isHard(out, "locations")).isFalse();
  }

  @Test
  void aGazetteerHit_isNotDemotedToAPreference() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("Powai", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.confidenceOf("locations")).isEqualTo(1.0);
    assertThat(ConfidenceGate.isHard(out, "locations")).isTrue();
  }

  @Test
  void oneInferredNameDragsTheWholeSlotDown_becauseTheFilterAppliesToTheListAtOnce() {
    ownData("Oberoi Splendor", id("Andheri East"));
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", null), new LocationRef("Oberoi Splendor", null)))
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.locations())
        .extracting(LocationRef::localityId)
        .containsExactly(id("Powai"), id("Andheri East"));
    assertThat(out.confidenceOf("locations")).isEqualTo(0.5);
  }

  @Test
  void ulweIsStillUnplaceable_whenNeitherLayerKnowsIt() {
    // the spec's canonical unplaceable name: no gazetteer row, no listing of ours mentions it
    Mockito.when(properties.findPlacementByPlaceName(Mockito.anyString(), Mockito.any()))
        .thenReturn(List.of());
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Ulwe", null)))
            .originalQuery("room in Ulwe")
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.locations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Ulwe");
    assertThat(out.confidence()).isNull();
  }

  @Test
  void aNameFromAnotherCity_doesNotCrossTheScopeBoundary() {
    SearchIntent in =
        SearchIntent.builder().locations(List.of(new LocationRef("Indiranagar", null))).build();

    SearchIntent scoped = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));
    assertThat(scoped.locations()).isNull();
    assertThat(scoped.unresolvedLocations()).containsExactly("Indiranagar");

    // …and an UNSET viewer still reaches it, because unscoped means every city (§4.11)
    SearchIntent unscoped = resolve(in, resolver);
    assertThat(unscoped.locations()).extracting(LocationRef::localityId).containsExactly(id("Indiranagar"));
  }

  @Test
  void anInferredNameNeverBindsAnExclusion_becauseNothingCanSoftenOne() {
    // excludeLocations is ALWAYS_HARD, so a 0.5 would be recorded and then ignored: binding this
    // would delete every listing in Powai on the strength of a society name we matched against our
    // own inventory. It stays unplaced and is surfaced instead — what happens today.
    ownData("Oberoi Splendor", id("Powai"));
    SearchIntent in =
        SearchIntent.builder()
            .excludeLocations(List.of(new LocationRef("Oberoi Splendor", null)))
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.excludeLocations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Oberoi Splendor");
  }

  @Test
  void aGazetteerExclusionStillBinds() {
    SearchIntent in =
        SearchIntent.builder().excludeLocations(List.of(new LocationRef("Powai", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.excludeLocations()).extracting(LocationRef::localityId).containsExactly(id("Powai"));
    assertThat(out.unresolvedLocations()).isNull();
  }

  // ---- ids that arrive already bound are still held to the scope (review finding 1) ----

  @Test
  void anIdBoundByAnEarlierStep_isStillCheckedAgainstTheScope() {
    // the keyword parser and the mock provider both emit refs that already carry ids. Binding a
    // name somewhere else must not be a way round the city boundary.
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Indiranagar", id("Indiranagar"))))
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.locations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Indiranagar");
  }

  @Test
  void anIdBoundByAnEarlierStep_survivesWhenItIsInScope() {
    SearchIntent in =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.locations()).containsExactly(new LocationRef("Powai", id("Powai")));
    assertThat(out.unresolvedLocations()).isNull();
  }

  @Test
  void aCommuteAnchorBoundOutsideTheScope_isUnboundAndRetriedByName() {
    SearchIntent in =
        SearchIntent.builder().commuteTo(new CommuteTo("Indiranagar", id("Indiranagar"), 30)).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.commuteTo().localityId()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Indiranagar");
  }

  @Test
  void anUnscopedViewerKeepsEveryPreBoundId() {
    // UNSET reaches every seeded city, so nothing an earlier step bound is taken away (§4.11)
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Indiranagar", id("Indiranagar"))))
            .build();

    assertThat(resolve(in, resolver).locations())
        .extracting(LocationRef::localityId)
        .containsExactly(id("Indiranagar"));
  }

  @Test
  void aCommutePlaceOnlyOurOwnListingsCanPlace_stillAnchorsTheCommute() {
    ownData("Oberoi Splendor", id("BKC"));
    SearchIntent in =
        SearchIntent.builder().commuteTo(new CommuteTo("Oberoi Splendor", null, 30)).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver, CityScope.of("Mumbai"));

    assertThat(out.commuteTo().localityId()).isEqualTo(id("BKC"));
    assertThat(out.unresolvedLocations()).isNull();
    assertThat(out.confidenceOf("commuteTo")).isEqualTo(0.5);
  }
}
