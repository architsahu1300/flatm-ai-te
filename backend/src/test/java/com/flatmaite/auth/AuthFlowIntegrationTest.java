package com.flatmaite.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired TestRestTemplate rest;

  private static String sessionCookie;

  @Test
  @Order(1)
  void register_issuesSessionCookie() {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/auth/register",
            json(Map.of("name", "Test User", "email", "it-test@flatmaite.test", "password", "password123")),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("fm_token=");
    sessionCookie = setCookie.split(";")[0];
  }

  @Test
  @Order(2)
  void register_duplicateEmail_conflicts() {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/auth/register",
            json(Map.of("name", "Dup", "email", "it-test@flatmaite.test", "password", "password123")),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @Order(3)
  void login_wrongPassword_unauthorized() {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/auth/login",
            json(Map.of("email", "it-test@flatmaite.test", "password", "wrong-password")),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Order(4)
  void protectedEndpoint_withoutCookie_is401() {
    ResponseEntity<Map> response = rest.getForEntity("/api/v1/me", Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Order(5)
  void session_withCookie_isAuthenticated() {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, sessionCookie);
    ResponseEntity<Map> response =
        rest.exchange("/api/v1/auth/session", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data.get("authenticated")).isEqualTo(true);
  }

  private static HttpEntity<Map<String, String>> json(Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
