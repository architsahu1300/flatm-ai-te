package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.PropertyType;
import com.flatmaite.listing.Property;
import com.flatmaite.listing.PropertyRepository;
import com.flatmaite.seed.SeedLocalities;
import com.flatmaite.user.UserRepository;
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
 * Resolution ladder step 2 against real Postgres: a native SQL query over {@code properties},
 * which a mock repository cannot meaningfully exercise. Runs on the seed profile (59 Mumbai
 * localities, 240 listings) so a real ownerId/localityId are available without hand-wiring fixture
 * rows for foreign keys the schema enforces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class LocalityResolverOwnDataTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired LocalityResolver resolver;
  @Autowired PropertyRepository properties;
  @Autowired UserRepository users;
  @Autowired SearchPipeline pipeline;

  private static final UUID GOREGAON_ID = SeedLocalities.id("Goregaon");
  private static final UUID MALAD_ID = SeedLocalities.id("Malad");
  private static final UUID KANDIVALI_ID = SeedLocalities.id("Kandivali");

  @Test
  void aSocietyNameNobodyCuratedStillResolves() {
    // "Hiranandani" is an alias of Powai in the gazetteer; use a name that is not
    UUID ownerId = users.findAll().iterator().next().getId();
    properties.save(
        Property.builder()
            .ownerId(ownerId)
            .localityId(GOREGAON_ID)
            .addressLine("Flat 4, Oberoi Splendor, Jogeshwari East")
            .societyName("Oberoi Splendor")
            .lat(19.1400)
            .lng(72.8600)
            .propertyType(PropertyType.APARTMENT)
            .bhk((short) 2)
            .build());
    resolver.reload();

    Placement placement = resolver.resolve("Oberoi Splendor", CityScope.of("Mumbai"));

    assertThat(placement.source()).isEqualTo(Placement.Source.OWN_DATA);
    assertThat(placement.localityIds()).containsExactly(GOREGAON_ID);
    assertThat(placement.lat()).isEqualTo(19.1400);
    assertThat(placement.confidence()).isEqualTo(0.5);
  }

  @Test
  void aNameInNeitherTheGazetteerNorOurListingsIsHonestlyUnplaced() {
    assertThat(resolver.resolve("Ulwe", CityScope.of("Mumbai")).source())
        .isEqualTo(Placement.Source.NONE);
  }

  @Test
  void ownDataMatchingIsCityScopedLikeTheGazetteer() {
    UUID ownerId = users.findAll().iterator().next().getId();
    properties.save(
        Property.builder()
            .ownerId(ownerId)
            .localityId(GOREGAON_ID)
            .addressLine("Flat 9, Oberoi Splendor Annexe, Jogeshwari East")
            .societyName("Oberoi Splendor")
            .lat(19.1400)
            .lng(72.8600)
            .propertyType(PropertyType.APARTMENT)
            .bhk((short) 2)
            .build());
    resolver.reload();

    assertThat(resolver.resolve("Oberoi Splendor", CityScope.of("Bangalore")).source())
        .isEqualTo(Placement.Source.NONE);
  }

  @Test
  void underscoreInAPlaceNameIsMatchedLiterally_notAsAWildcard() {
    // "_" is a single-character LIKE wildcard unless escaped: an unescaped needle would also match
    // "SunXCity" (X standing in for the wildcard), pulling in a locality that has nothing to do
    // with the literal name the user typed.
    UUID ownerId = users.findAll().iterator().next().getId();
    properties.save(
        Property.builder()
            .ownerId(ownerId)
            .localityId(MALAD_ID)
            .addressLine("Flat 1, Sun_City Residency, Malad West")
            .societyName("Sun_City Residency")
            .lat(19.1874)
            .lng(72.8484)
            .propertyType(PropertyType.APARTMENT)
            .bhk((short) 1)
            .build());
    properties.save(
        Property.builder()
            .ownerId(ownerId)
            .localityId(KANDIVALI_ID)
            .addressLine("Flat 2, SunXCity Towers, Kandivali West")
            .societyName("SunXCity Towers")
            .lat(19.2045)
            .lng(72.8519)
            .propertyType(PropertyType.APARTMENT)
            .bhk((short) 1)
            .build());
    resolver.reload();

    Placement placement = resolver.resolve("Sun_City", CityScope.of("Mumbai"));

    assertThat(placement.source()).isEqualTo(Placement.Source.OWN_DATA);
    assertThat(placement.localityIds()).containsExactly(MALAD_ID);
  }

  // ---- the ladder actually runs in a real search (ruling R9) ----

  @Test
  void aSocietyNameOnlyOurListingsKnow_reachesTheSearchAsAnInferredPlace() {
    UUID ownerId = users.findAll().iterator().next().getId();
    properties.save(
        Property.builder()
            .ownerId(ownerId)
            .localityId(KANDIVALI_ID)
            .addressLine("Flat 11, Lokhandwala Infinity, Kandivali East")
            .societyName("Lokhandwala Infinity")
            .lat(19.2045)
            .lng(72.8519)
            .propertyType(PropertyType.APARTMENT)
            .bhk((short) 2)
            .build());
    resolver.reload();

    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new SearchIntent.LocationRef("Lokhandwala Infinity", null)))
            .originalQuery("room in Lokhandwala Infinity")
            .build();

    SearchIntent resolved = IntentLocalities.resolve(intent, resolver, CityScope.of("Mumbai"));

    // it is placed, not surfaced as unplaceable…
    assertThat(resolved.unresolvedLocations()).isNull();
    assertThat(resolved.locations())
        .extracting(SearchIntent.LocationRef::localityId)
        .containsExactly(KANDIVALI_ID);
    // …at the ladder's fixed 0.5, so the gate ranks by it instead of filtering on it…
    assertThat(resolved.confidenceOf("locations")).isEqualTo(0.5);
    assertThat(ConfidenceGate.isHard(resolved, "locations")).isFalse();
    // …and the pipeline never restamps those ids as a curated gazetteer statement
    assertThat(pipeline.placementOf(resolved).source()).isNotEqualTo(Placement.Source.GAZETTEER);
  }

  @Test
  void ulweIsStillUnplaceableThroughTheWholeLadder() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new SearchIntent.LocationRef("Ulwe", null)))
            .originalQuery("room in Ulwe")
            .build();

    SearchIntent resolved = IntentLocalities.resolve(intent, resolver, CityScope.of("Mumbai"));

    assertThat(resolved.locations()).isNull();
    assertThat(resolved.unresolvedLocations()).containsExactly("Ulwe");
    assertThat(pipeline.placementOf(resolved)).isEqualTo(Placement.none());
  }
}
