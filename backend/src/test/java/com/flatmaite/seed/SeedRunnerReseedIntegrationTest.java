package com.flatmaite.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The seed profile promises "re-seeding upserts rather than duplicating". This is exercised for
 * real, not by two same-shape process restarts (which draw an identical {@code Random(42)}
 * sequence and so never disturb a listing's image collection), but by calling this JVM's single
 * {@code SeedRunner} bean's {@code run()} a second time: its {@code rng} field is a mutable,
 * already-advanced {@code Random}, so the second pass draws a different sequence than the first
 * and recomputes different per-listing photo counts — the same kind of divergence that, across
 * two real deployments seeded from different gazetteer/listing-count shapes, once raced the raw
 * JDBC stray-image sweep in {@code SeedRunner.seedListings} against Hibernate's own deferred
 * orphan-removal delete and threw {@code StaleObjectStateException} (see task-4-report.md, "Fix
 * report: idempotent re-seed"). This test proves the fix holds without depending on manual
 * console evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class SeedRunnerReseedIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired SeedRunner seedRunner;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void reseedingWithinTheSameProcess_upsertsInsteadOfThrowing() throws Exception {
    // the "seed" profile's ApplicationRunner already seeded once at context startup
    Counts afterFirstRun = countRows();
    assertThat(afterFirstRun).isEqualTo(new Counts(59, 240, 121, 35));

    seedRunner.run(new DefaultApplicationArguments());

    Counts afterSecondRun = countRows();
    assertThat(afterSecondRun)
        .as("re-seeding must upsert, not duplicate or throw")
        .isEqualTo(afterFirstRun);
  }

  private Counts countRows() {
    return new Counts(
        count("localities"), count("listings"), count("users"), count("flatmate_profiles"));
  }

  private int count(String table) {
    Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return n == null ? 0 : n;
  }

  private record Counts(int localities, int listings, int users, int flatmates) {}
}
