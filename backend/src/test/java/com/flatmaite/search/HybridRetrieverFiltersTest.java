package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.listing.ListingFilters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * budgetMin's hop from a parsed query all the way to the hard SQL filter — the plumbing Critical 1
 * fixed. A prior review found budgetMin extracted, prompted and tested everywhere except here.
 */
class HybridRetrieverFiltersTest {

  private HybridRetriever retriever;
  private KeywordIntentParser parser;

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", "hiranandani"),
                locality("Andheri East", "andheri east", "andheri"),
                locality("Andheri West", "andheri west", "andheri"),
                locality("BKC", "bandra kurla complex")));
    LocalityResolver resolver = new LocalityResolver(repo);
    resolver.load();
    parser = new KeywordIntentParser(resolver);
    CommuteEstimator estimator = Mockito.mock(CommuteEstimator.class);
    FlatmaiteProperties props = new FlatmaiteProperties();
    // constructor arguments follow HybridRetriever's field declaration order
    retriever = new HybridRetriever(null, null, estimator, resolver, props);
  }

  @Test
  void withinMinutesOfPlace_parsesAsACeiling_andReachesTheFilter() {
    SearchIntent intent = parser.parse("room within 20 min of bkc, 25k");

    assertThat(intent.budgetMax()).isEqualTo(25000);
    assertThat(intent.budgetMin()).isNull();
    assertThat(intent.commuteTo().place()).isEqualTo("BKC");
    assertThat(intent.commuteTo().maxMinutes()).isEqualTo(20);

    ListingFilters filters = retriever.toFilters(intent);
    assertThat(filters.budgetMax()).isEqualTo(27500); // 25000 * 1.1 headroom
    assertThat(filters.budgetMin()).isNull();
  }

  @Test
  void moreThan_parsesAsAFloor_andReachesTheFilterWithNoHeadroom() {
    SearchIntent intent = parser.parse("flat in andheri more than 30000");

    assertThat(intent.budgetMin()).isEqualTo(30000);
    assertThat(intent.budgetMax()).isNull();

    assertThat(retriever.toFilters(intent).budgetMin()).isEqualTo(30000);
  }
}
