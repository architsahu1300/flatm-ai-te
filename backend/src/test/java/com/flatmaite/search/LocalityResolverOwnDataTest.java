package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.PropertyType;
import com.flatmaite.listing.Property;
import com.flatmaite.listing.PropertyRepository;
import com.flatmaite.seed.SeedLocalities;
import com.flatmaite.user.UserRepository;
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

  private static final UUID GOREGAON_ID = SeedLocalities.id("Goregaon");

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
}
