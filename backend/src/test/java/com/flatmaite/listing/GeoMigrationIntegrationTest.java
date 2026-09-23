package com.flatmaite.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class GeoMigrationIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void earthdistanceIsInstalledAndPropertiesAreIndexedByPoint() {
    List<String> extensions =
        jdbc.queryForList("SELECT extname FROM pg_extension", String.class);
    assertThat(extensions).contains("cube", "earthdistance");

    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'properties'", String.class);
    assertThat(indexes).contains("idx_properties_geo");
  }

  @Test
  void localityNamesAreUniquePerCityNotGlobally() {
    List<String> constraints =
        jdbc.queryForList(
            """
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'localities'::regclass AND contype = 'u'
            """,
            String.class);
    assertThat(constraints).contains("localities_city_name_key");
    assertThat(constraints).doesNotContain("localities_name_key");

    // the same name in two cities is now legal
    jdbc.update(
        """
        INSERT INTO localities (name, city, lat, lng) VALUES
          ('MG Road', 'Mumbai', 19.0, 72.8),
          ('MG Road', 'Bangalore', 12.97, 77.6)
        """);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM localities WHERE name = 'MG Road'", Integer.class);
    assertThat(count).isEqualTo(2);
  }
}
