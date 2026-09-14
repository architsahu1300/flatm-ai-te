package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IntentLocalitiesTest {

  private LocalityResolver resolver;

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes());
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
                locality("BKC", "bandra kurla complex", "bandra kurla")));
    resolver = new LocalityResolver(repo);
    resolver.load();
  }

  @Test
  void unknownName_movesToUnresolved_andIntoFreeText() {
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Atlantis", null)))
            .freeText("room in atlantis")
            .originalQuery("room in Atlantis")
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.locations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Atlantis");
    assertThat(out.freeText()).isEqualTo("room in atlantis"); // already mentioned — not appended twice
  }

  @Test
  void unknownName_isAppendedToFreeText_whenAbsent() {
    SearchIntent in =
        SearchIntent.builder().locations(List.of(new LocationRef("Atlantis", null))).freeText("quiet room").build();

    assertThat(IntentLocalities.resolve(in, resolver).freeText()).isEqualTo("quiet room Atlantis");
  }

  @Test
  void ambiguousAlias_expandsToEveryLocality() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("andheri", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.locations()).extracting(LocationRef::localityId).containsExactlyInAnyOrder(id("Andheri East"), id("Andheri West"));
    assertThat(out.locations()).extracting(LocationRef::name).containsExactlyInAnyOrder("Andheri East", "Andheri West");
  }

  @Test
  void alreadyResolvedRef_keepsItsIdAndName() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("My Powai", id("Powai")))).build();

    assertThat(IntentLocalities.resolve(in, resolver).locations()).containsExactly(new LocationRef("My Powai", id("Powai")));
  }

  @Test
  void commutePlace_isCanonicalised_orMarkedUnresolved() {
    SearchIntent known = SearchIntent.builder().commuteTo(new CommuteTo("bandra kurla", null, 20)).build();
    SearchIntent unknown = SearchIntent.builder().commuteTo(new CommuteTo("Nowhere", null, 20)).build();

    CommuteTo resolved = IntentLocalities.resolve(known, resolver).commuteTo();
    assertThat(resolved.localityId()).isEqualTo(id("BKC"));
    assertThat(resolved.place()).isEqualTo("BKC");
    assertThat(resolved.maxMinutes()).isEqualTo(20);

    SearchIntent out = IntentLocalities.resolve(unknown, resolver);
    assertThat(out.commuteTo().localityId()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Nowhere");
  }

  @Test
  void excludedUnknownName_isSurfacedToo() {
    SearchIntent in = SearchIntent.builder().excludeLocations(List.of(new LocationRef("Atlantis", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.excludeLocations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Atlantis");
  }

  @Test
  void nothingToResolve_returnsTheSameIntent() {
    SearchIntent in = SearchIntent.builder().budgetMax(20000).build();

    assertThat(IntentLocalities.resolve(in, resolver)).isSameAs(in);
  }

  @Test
  void aLocalityBothRequestedAndExcluded_exclusionWins() {
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", null)))
            .excludeLocations(List.of(new LocationRef("Powai", null)))
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

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

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.locations()).extracting(LocationRef::localityId).containsExactly(id("Andheri East"));
  }

  @Test
  void unresolvedLocationsOnly_stillAppendsToFreeText() {
    // reachable if a model emits only unresolvedLocations with no locations/exclude/commute set
    SearchIntent in =
        SearchIntent.builder().unresolvedLocations(List.of("Atlantis")).freeText("quiet room").build();

    assertThat(IntentLocalities.resolve(in, resolver).freeText()).isEqualTo("quiet room Atlantis");
  }
}
