package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.flatmate.FlatmateProfile;
import com.flatmaite.flatmate.FlatmateProfileRepository;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.search.HybridRetriever.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Fusion against real Postgres+pgvector with the deterministic seed (Random(42)) and the mock
 * embedding provider. Proves the lexical ranking actually fires and that every candidate carries
 * a normalized retrieval score.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class HybridRetrieverIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired HybridRetriever retriever;
  @Autowired ListingQueryService listingQueryService;
  @Autowired FlatmateProfileRepository flatmateProfiles;

  @Test
  void everyCandidate_carriesNormalizedScore_topIsOne() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .originalQuery("a quiet room")
            .freeText("a quiet room")
            .build();

    List<Candidate> out = retriever.retrieveListings(intent);

    assertThat(out).isNotEmpty();
    assertThat(out.get(0).retrieval().score()).isEqualTo(1.0);
    for (int i = 0; i < out.size(); i++) {
      double score = out.get(i).retrieval().score();
      assertThat(score).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
      if (i > 0) {
        assertThat(score).isLessThanOrEqualTo(out.get(i - 1).retrieval().score());
      }
    }
  }

  @Test
  void listings_lexicalTermOnlyInFurnishedDescriptions_firesAndRanksFirst() {
    // "wardrobe" appears only in FULLY_FURNISHED seed descriptions ("bed, wardrobe and more")
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .originalQuery("room with a wardrobe")
            .freeText("wardrobe")
            .build();

    List<Candidate> out = retriever.retrieveListings(intent);
    List<UUID> lexicalIds =
        out.stream().filter(c -> c.retrieval().lexicalHit()).map(Candidate::id).toList();

    assertThat(lexicalIds).isNotEmpty();
    // fewer active seed listings than VECTOR_LIMIT → every lexical hit is also a vector hit, and
    // RRF places anything in both rankings ahead of anything in one
    assertThat(out.get(0).retrieval().lexicalHit()).isTrue();
    List<Listing> hydrated = listingQueryService.hydrate(lexicalIds);
    assertThat(hydrated)
        .isNotEmpty()
        .allSatisfy(l -> assertThat(l.getFurnishing()).isEqualTo(Furnishing.FULLY_FURNISHED));
  }

  @Test
  void listings_multiTermQuery_matchesAnyTerm_notAll() {
    // "wardrobe" appears only in FULLY_FURNISHED seed descriptions and "essentials" only in
    // SEMI_FURNISHED ones — never both in one listing. The old AND semantics returned nothing
    // for this query; OR semantics must surface listings of both kinds.
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .originalQuery("room with a wardrobe or at least the essentials")
            .freeText("wardrobe essentials")
            .build();

    List<Candidate> out = retriever.retrieveListings(intent);
    List<UUID> lexicalIds =
        out.stream().filter(c -> c.retrieval().lexicalHit()).map(Candidate::id).toList();
    List<Listing> hydrated = listingQueryService.hydrate(lexicalIds);

    assertThat(hydrated)
        .extracting(Listing::getFurnishing)
        .contains(Furnishing.FULLY_FURNISHED, Furnishing.SEMI_FURNISHED)
        .doesNotContain(Furnishing.UNFURNISHED);
  }

  @Test
  void flatmates_lexicalHeadlineTerm_firesAndRanksFirst() {
    // "flatmate" appears only in the headlines of seed profiles that already have a flat
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.FLATMATES)
            .originalQuery("someone who already has a flat and wants a flatmate")
            .freeText("flatmate")
            .build();

    List<Candidate> out = retriever.retrieveFlatmates(intent, null);
    List<UUID> lexicalIds =
        out.stream().filter(c -> c.retrieval().lexicalHit()).map(Candidate::id).toList();

    assertThat(lexicalIds).isNotEmpty();
    assertThat(out.get(0).retrieval().lexicalHit()).isTrue();
    assertThat(out.get(0).retrieval().score()).isEqualTo(1.0);
    List<FlatmateProfile> hydrated = new ArrayList<>();
    flatmateProfiles.findAllById(lexicalIds).forEach(hydrated::add);
    assertThat(hydrated)
        .isNotEmpty()
        .allSatisfy(fp -> assertThat(fp.getHeadline().toLowerCase(Locale.ROOT)).contains("flatmate"));
  }
}
