package com.flatmaite.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The seeded listing photos must survive a host whose filesystem is ephemeral — a free tier
 * wipes the disk on every redeploy, restart and idle spin-down, and the seeded
 * {@code listing_images} rows point at fixed {@code /uploads/seed/...} URLs.
 *
 * <p>So this test points {@code upload-dir} at a directory that does not exist and asserts the
 * images are served anyway, from the classpath. If someone reinstates the old behaviour of
 * writing the SVGs into UPLOAD_DIR at seed time, this fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SeedImageServingIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    // deliberately a path that was never created — nothing may be read from disk here
    r.add("flatmaite.storage.upload-dir", () -> "/tmp/flatmaite-upload-dir-that-does-not-exist");
  }

  @Autowired TestRestTemplate http;

  @Test
  void aSeededListingPhotoIsServedWithNoUploadDirectoryOnDisk() {
    ResponseEntity<String> res = http.getForEntity("/uploads/seed/img-01.svg", String.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).startsWith("<svg");
    assertThat(res.getBody()).contains("Flatm'AI'te sample photo");
  }

  @Test
  void everyUrlTheSeedCanMintResolves() {
    // SeedRunner mints /uploads/seed/img-%02d.svg for 1..IMAGE_COUNT
    for (int i = 1; i <= 15; i++) {
      String url = "/uploads/seed/img-%02d.svg".formatted(i);

      assertThat(http.getForEntity(url, String.class).getStatusCode())
          .describedAs(url)
          .isEqualTo(HttpStatus.OK);
    }
  }

  @Test
  void anUnknownUploadIsStillANotFound_ratherThanLeakingTheClasspath() {
    ResponseEntity<String> res = http.getForEntity("/uploads/seed/img-99.svg", String.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
