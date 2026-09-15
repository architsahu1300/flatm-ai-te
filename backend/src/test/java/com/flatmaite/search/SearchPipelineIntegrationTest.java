package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.SearchTarget;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end pipeline against real Postgres+pgvector with the seed data and the mock AI provider:
 * intent extraction → hard filters → vector retrieval → scoring → explanations.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // the seed profile turns the web server off for CLI seeding — turn it back on here
    properties = "spring.main.web-application-type=servlet")
@Testcontainers
@ActiveProfiles("seed")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchPipelineIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired TestRestTemplate rest;
  @Autowired SearchPipeline pipeline;

  private static String sessionId;
  private static String anonCookie;

  @Test
  @Order(1)
  @SuppressWarnings("unchecked")
  void aiSearch_extractsIntent_andRanksSeedListings() {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/search",
            json(Map.of("query", "Find me a room near BKC under 25k, no smokers")),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");

    assertThat(intent.get("budgetMax")).isEqualTo(25000);
    assertThat(intent.get("searchTarget")).isEqualTo("PROPERTIES");
    List<Map<String, Object>> homes = (List<Map<String, Object>>) data.get("homes");
    assertThat(homes).isNotEmpty();
    Map<String, Object> top = homes.get(0);
    assertThat((Integer) top.get("matchScore")).isBetween(1, 100);
    assertThat((List<?>) top.get("scoreBreakdown")).isNotEmpty();
    assertThat((List<?>) top.get("matchReasons")).isNotEmpty();
    // ranked: scores non-increasing among the exact matches. A thin exact page (this seed's BKC
    // results land just under MIN_RESULTS) tops itself up with a rescue rung near miss, which sorts
    // below every exact match regardless of score — so the non-increasing check applies only to the
    // exact-match prefix, not across that boundary.
    for (int i = 1; i < homes.size(); i++) {
      if (Boolean.TRUE.equals(homes.get(i).get("nearMiss"))) {
        break;
      }
      assertThat((Integer) homes.get(i).get("matchScore"))
          .isLessThanOrEqualTo((Integer) homes.get(i - 1).get("matchScore"));
    }

    sessionId = (String) data.get("sessionId");
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("fm_anon=");
    anonCookie = setCookie.split(";")[0];
  }

  @Test
  @Order(2)
  @SuppressWarnings("unchecked")
  void refine_cheaper_reducesBudget_andKeepsSession() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, anonCookie);
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/refine",
            new HttpEntity<>(Map.of("query", "show me cheaper", "sessionId", sessionId), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    assertThat((Integer) intent.get("budgetMax")).isLessThan(25000);
    // a budget tweak must not replace the residual free text with the word "cheaper"
    assertThat(intent.get("freeText")).isEqualTo("Find me a room near BKC under 25k, no smokers");
    assertThat(data.get("sessionId")).isEqualTo(sessionId);
  }

  @Test
  @Order(3)
  @SuppressWarnings("unchecked")
  void flatmateSearch_returnsPeople() {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/search",
            json(Map.of("query", "find a quiet flatmate who does not smoke")),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data.get("intent")).extracting(i -> ((Map<String, Object>) i).get("searchTarget"))
        .isEqualTo("FLATMATES");
    assertThat((List<?>) data.get("flatmates")).isNotEmpty();
  }

  @Test
  @Order(4)
  @SuppressWarnings("unchecked")
  void ambiguousFollowUp_staysARefinement_whenTheModelHasNoOpinion() {
    // one anchor + a housing noun → AMBIGUOUS; the mock offers no mode → the detector's default (REFINE)
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, anonCookie);
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/refine",
            new HttpEntity<>(Map.of("query", "flats in powai", "sessionId", sessionId), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    // budget from the earlier turns survives, the locality is added
    assertThat((Integer) intent.get("budgetMax")).isLessThan(25000);
    List<Map<String, Object>> locations = (List<Map<String, Object>>) intent.get("locations");
    assertThat(locations).extracting(l -> l.get("name")).contains("Powai");
    // A refinement never carries the fresh-search note. It does name the commute radius as a
    // preference: no turn ever stated a minute count, so ~30 min is our default, not the user's word.
    String note = (String) data.get("note");
    assertThat(note).doesNotContain("fresh search");
    assertThat(note).contains("preferences, not filters").contains("commute time");
    // And it does NOT name budget as a preference, because "show me cheaper" commanded it:
    assertThat(note).doesNotContain("budget");
  }

  @Test
  @Order(5)
  @SuppressWarnings("unchecked")
  void freshCue_startsOver_andSaysSo() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, anonCookie);
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/refine",
            new HttpEntity<>(
                Map.of("query", "forget that, single sharing room in goregaon 20k", "sessionId", sessionId), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    assertThat(intent.get("budgetMax")).isEqualTo(20000);
    assertThat(intent.get("commuteTo")).isNull();
    assertThat((String) data.get("note")).contains("fresh search");
  }

  @Test
  @Order(6)
  @SuppressWarnings("unchecked")
  void overTightFloor_isRescuedInsteadOfOfferingOnlyARelaxer() {
    // max seed rent is well under 200000 — this floor used to admit nothing until the user clicked
    // a relaxer; the rescue ladder now drops the minimum itself (the only hard filter here) and
    // tops the page up with the note explaining it, so relaxers (empty-page only) no longer fire.
    ResponseEntity<Map> response =
        rest.postForEntity("/api/v1/ai/search", json(Map.of("query", "flat more than 200000")), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    assertThat(intent.get("budgetMin")).isEqualTo(200000);

    List<Map<String, Object>> homes = (List<Map<String, Object>>) data.get("homes");
    assertThat(homes).isNotEmpty();
    assertThat(homes).allSatisfy(h -> assertThat(h.get("nearMiss")).isEqualTo(true));
    assertThat(homes).allSatisfy(h -> assertThat((String) h.get("nearMissReason")).contains("minimum budget"));
    assertThat((String) data.get("note")).contains("minimum budget");
    assertThat((List<Map<String, Object>>) data.get("relaxers")).isEmpty();
  }

  @Test
  @Order(7)
  @SuppressWarnings("unchecked")
  void impossibleBhk_isRescued_butTheExplicitExclusionStillHolds() {
    ResponseEntity<Map> first =
        rest.postForEntity("/api/v1/ai/search", json(Map.of("query", "flat in mumbai")), Map.class);
    Map<String, Object> firstData = (Map<String, Object>) first.getBody().get("data");
    String freshSessionId = (String) firstData.get("sessionId");
    String freshCookie = first.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, freshCookie);

    // bhk 99 admits nothing (seed tops out at 3) — the ladder drops it (the only impossible filter
    // here) rather than leaving the page empty for a "Start broader" button. The exclusion is a
    // promise (ConfidenceGate.ALWAYS_HARD) and is never on the ladder, so it must still hold.
    Map<String, Object> excludeRef = new java.util.HashMap<>();
    excludeRef.put("name", "Powai");
    excludeRef.put("localityId", null);
    Map<String, Object> impossibleIntent =
        Map.of("bhk", Map.of("min", 99, "max", 99), "excludeLocations", List.of(excludeRef));

    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/apply",
            new HttpEntity<>(Map.of("intent", impossibleIntent, "sessionId", freshSessionId), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    List<Map<String, Object>> homes = (List<Map<String, Object>>) data.get("homes");
    assertThat(homes).isNotEmpty();
    assertThat(homes).allSatisfy(h -> assertThat(h.get("nearMiss")).isEqualTo(true));
    assertThat(homes)
        .allSatisfy(
            h -> {
              Map<String, Object> home = (Map<String, Object>) h.get("home");
              assertThat(home.get("localityName")).isNotEqualTo("Powai");
            });
    assertThat((String) data.get("note")).contains("size");
  }

  @Test
  @SuppressWarnings("unchecked")
  void anOverTightSearchIsToppedUpWithMarkedNearMisses() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(9000)
            .originalQuery("flat in colaba under 9k")
            .build();

    SearchDtos.AiSearchResponse res = pipeline.search(intent, null, "test", UUID.randomUUID());

    assertThat(res.homes()).isNotEmpty();
    assertThat(res.homes().stream().filter(SearchDtos.AiResult::nearMiss)).isNotEmpty();
    assertThat(res.note()).contains("nearby option");
    // every exact match sorts above every near miss
    int firstNearMiss = -1;
    for (int i = 0; i < res.homes().size(); i++) {
      if (res.homes().get(i).nearMiss() && firstNearMiss < 0) {
        firstNearMiss = i;
      } else if (!res.homes().get(i).nearMiss()) {
        assertThat(firstNearMiss).as("an exact match appeared after a near miss").isLessThan(0);
      }
    }
    assertThat(res.homes().stream().filter(SearchDtos.AiResult::nearMiss))
        .allSatisfy(r -> assertThat(r.nearMissReason()).isNotBlank());
  }

  private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
