package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchDtos.AiResult;
import com.flatmaite.search.SearchDtos.AiSearchResponse;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A named home locality admits its ~25-minute neighbourhood, ranks exact matches first, labels the
 * rest with their distance, and says so in the note. Against the deterministic seed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class LocationWideningIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired HybridRetriever retriever;
  @Autowired SearchPipeline pipeline;
  @Autowired LocalityResolver resolver;
  @Autowired LocalityRepository localities;
  @Autowired ListingQueryService listingQueryService;

  private UUID idOf(String name) {
    return resolver.resolve(name).orElseThrow().localityIds().get(0);
  }

  private SearchIntent homeIn(String name) {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .locations(List.of(new LocationRef(name, idOf(name))))
        .originalQuery("room in " + name)
        .freeText("room in " + name)
        .build();
  }

  /** The first western-suburb locality that actually has an active seed listing. */
  private String anchoredLocality() {
    for (String name : List.of("Goregaon", "Malad", "Ram Mandir", "Jogeshwari", "Andheri West", "Andheri East")) {
      long total =
          listingQueryService
              .findIds(ListingFilters.builder().localityIds(List.of(idOf(name))).build(), ListingQueryService.Sort.NEWEST, 0, 1)
              .total();
      if (total > 0) {
        return name;
      }
    }
    throw new AssertionError("no seeded active listing in the Goregaon cluster");
  }

  @Test
  void seedHasTheWiderLocalityTable() {
    assertThat(localities.count()).isGreaterThanOrEqualTo(35);
    assertThat(resolver.resolve("Ram Mandir")).isPresent();
    assertThat(resolver.resolve("Andheri").orElseThrow().localityIds()).hasSize(2);
    assertThat(resolver.resolve("parel").orElseThrow().canonicalName()).isEqualTo("Parel");
  }

  @Test
  void namedLocality_admitsItsNeighbourhood_requestedFirst() {
    List<UUID> admitted = retriever.admittedLocalityIds(homeIn("Goregaon"));

    assertThat(admitted.get(0)).isEqualTo(idOf("Goregaon"));
    assertThat(admitted).contains(idOf("Ram Mandir"), idOf("Malad"), idOf("Jogeshwari"));
    assertThat(admitted).doesNotContain(idOf("Colaba"), idOf("Thane"));
  }

  @Test
  void exclusion_removesFromTheAdmittedSet() {
    SearchIntent intent =
        homeIn("Goregaon").toBuilder()
            .excludeLocations(List.of(new LocationRef("Malad", idOf("Malad"))))
            .build();

    assertThat(retriever.admittedLocalityIds(intent)).contains(idOf("Ram Mandir")).doesNotContain(idOf("Malad"));
  }

  @Test
  void nearbyHomes_areLabelledAndNoted_exactOnesAreNot() {
    String name = anchoredLocality();

    AiSearchResponse r = pipeline.search(homeIn(name), null, null, UUID.randomUUID());

    List<AiResult> exact = r.homes().stream().filter(h -> h.commuteLabel() == null).toList();
    List<AiResult> nearby = r.homes().stream().filter(h -> h.commuteLabel() != null).toList();
    assertThat(exact).isNotEmpty();
    assertThat(exact)
        .allSatisfy(h -> assertThat(h.scoreBreakdown())
            .anySatisfy(cmp -> assertThat(cmp.detail()).contains("one of your preferred areas")));
    assertThat(nearby).isNotEmpty();
    assertThat(nearby).allSatisfy(h -> assertThat(h.commuteLabel()).contains("min from " + name));
    assertThat(r.note()).contains("nearby areas within ~25 min");
  }

  @Test
  void aGuessedAreaIsNotDescribedAsANearbyRing_becauseTheSearchWasCityWide() {
    String name = anchoredLocality();
    SearchIntent guessed =
        homeIn(name).toBuilder().confidence(java.util.Map.of("locations", 0.5)).build();

    AiSearchResponse r = pipeline.search(guessed, null, null, UUID.randomUUID());

    // nothing narrowed the query to a ring, so "within ~25 min" would be a claim about these rows
    // that the query never made true — the preferences sentence is what says something honest here
    assertThat(r.note()).doesNotContain("nearby areas within");
    assertThat(r.note()).contains("preferences, not filters").contains("area");
  }
}
