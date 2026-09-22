package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.seed.SeedLocalities;
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
    // results land just under MIN_RESULTS) tops itself up with a later-tier near miss, which sorts
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
  void overTightFloor_offersACountedRelaxer_ratherThanQuietlyDroppingTheFloor() {
    // max seed rent is well under 200000. The old ladder filled the page by dropping the minimum
    // itself; nothing does that any longer — the floor is not distance and it is not the budget
    // band, so it stays enforced and giving it up becomes a button the user presses.
    ResponseEntity<Map> response =
        rest.postForEntity("/api/v1/ai/search", json(Map.of("query", "flat more than 200000")), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    assertThat(intent.get("budgetMin")).isEqualTo(200000);

    List<Map<String, Object>> homes = (List<Map<String, Object>>) data.get("homes");
    assertThat(homes).isEmpty();
    List<Map<String, Object>> relaxers = (List<Map<String, Object>>) data.get("relaxers");
    assertThat(relaxers).isNotEmpty();
    assertThat(relaxers)
        .anySatisfy(
            r -> {
              assertThat((String) r.get("label")).containsIgnoringCase("minimum");
              assertThat(((Number) r.get("extraResults")).longValue()).isGreaterThan(0);
            });
  }

  @Test
  @Order(7)
  @SuppressWarnings("unchecked")
  void impossibleBhk_isOfferedAsARelaxer_andTheExplicitExclusionSurvivesTheOffer() {
    ResponseEntity<Map> first =
        rest.postForEntity("/api/v1/ai/search", json(Map.of("query", "flat in mumbai")), Map.class);
    Map<String, Object> firstData = (Map<String, Object>) first.getBody().get("data");
    String freshSessionId = (String) firstData.get("sessionId");
    String freshCookie = first.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, freshCookie);

    // bhk 99 admits nothing (seed tops out at 3). The old ladder dropped it to fill the page; now
    // size is neither distance nor the budget band, so it stays enforced, the page stays honestly
    // empty and the compromise is offered as a counted relaxer instead. The exclusion is a promise
    // (ConfidenceGate.ALWAYS_HARD), so even the offer must still carry it.
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
    assertThat(homes).isEmpty();

    // a relaxer for the one impossible filter, by name — not the "Start broader" reset, which would
    // drop every other filter as well to relax this one
    List<Map<String, Object>> relaxers = (List<Map<String, Object>>) data.get("relaxers");
    assertThat(relaxers).extracting(r -> (String) r.get("label")).doesNotContain("Start broader");
    Map<String, Object> sizeRelaxer =
        relaxers.stream()
            .filter(r -> ((String) r.get("label")).contains("size"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no size relaxer among " + relaxers));

    assertThat(((Number) sizeRelaxer.get("extraResults")).longValue()).isGreaterThan(0);
    // it clears bhk and nothing else — and never the exclusion, which is a promise
    Map<String, Object> relaxed = (Map<String, Object>) sizeRelaxer.get("relaxedIntent");
    assertThat(relaxed.get("bhk")).isNull();
    assertThat((List<Map<String, Object>>) relaxed.get("excludeLocations"))
        .extracting(l -> l.get("name"))
        .contains("Powai");
  }

  @Test
  void anOverTightSearchIsToppedUpWithMarkedNearMisses() {
    // Colaba's only seed listing is ₹35,500, so a ₹33,000 search has nothing under budget there or
    // within five kilometres of it. The page is topped up from the labelled +10% band and from
    // nowhere else.
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(33000)
            .originalQuery("flat in colaba under 33000")
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
    // nothing on the page is over budget without saying so, and nothing is over the band at all
    assertThat(res.homes())
        .allSatisfy(
            r -> {
              int rent = r.home().rentMonthly();
              assertThat(rent).isLessThanOrEqualTo(36300); // 33000 × 1.1, the band's own ceiling
              if (rent > 33000) {
                assertThat(r.nearMiss()).isTrue();
                assertThat(r.nearMissReason()).contains("over your budget");
              }
            });
  }

  @Test
  void aSearchWithNothingInTheBandStaysEmpty_andOffersACountedRaise() {
    // the same place with a budget nothing can reach: no tier may invent a page out of it
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(9000)
            .originalQuery("flat in colaba under 9k")
            .build();

    SearchDtos.AiSearchResponse res = pipeline.search(intent, null, "test", UUID.randomUUID());

    assertThat(res.homes()).isEmpty();
    assertThat(res.relaxers()).isNotEmpty();
    assertThat(res.relaxers())
        .anySatisfy(
            r -> {
              assertThat(r.label()).containsIgnoringCase("budget");
              assertThat(r.extraResults()).isGreaterThan(0);
              assertThat(r.relaxedIntent().budgetMax()).isGreaterThan(9000);
            });
    // the offer is an offer: the user's own budget has not moved
    assertThat(res.intent().budgetMax()).isEqualTo(9000);
  }

  @Test
  void aSlotWithNoHandWrittenRelaxer_stillGetsACountedOfferOfItsOwn() {
    // No seed listing has a deposit of ₹1, so the page is empty. Before the ladder stopped dropping
    // filters by itself, deposit was one of nine gated slots with no offer at all — the only way out
    // was "Start broader", which drops every other filter to relax this one.
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .maxDeposit(1)
            .verifiedOnly(true)
            .excludeLocations(List.of(new SearchIntent.LocationRef("Kurla", SeedLocalities.id("Kurla"))))
            .originalQuery("flat with a deposit of one rupee")
            .build();

    SearchDtos.AiSearchResponse res = pipeline.search(intent, null, "test", UUID.randomUUID());

    assertThat(res.homes()).isEmpty();
    assertThat(res.relaxers()).extracting(SearchDtos.Relaxer::label).doesNotContain("Start broader");
    SearchDtos.Relaxer deposit =
        res.relaxers().stream()
            .filter(r -> r.label().contains("deposit"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no deposit relaxer among " + res.relaxers()));

    assertThat(deposit.extraResults()).isGreaterThan(0);
    assertThat(deposit.relaxedIntent().maxDeposit()).isNull();
    // it gives up the deposit and nothing else: the exclusion the user typed is still a promise
    assertThat(deposit.relaxedIntent().excludeLocations())
        .extracting(SearchIntent.LocationRef::name)
        .contains("Kurla");
    assertThat(deposit.relaxedIntent().verifiedOnly()).isTrue();
    // and the user's own intent is untouched until they click it
    assertThat(res.intent().maxDeposit()).isEqualTo(1);
  }

  @Test
  void theReportedBug_neitherKurlaNorGoregaonIsPassedOffAsAKandivaliMatch() {
    // "single sharing room in Kandivali under 15k" used to return a ₹12,000 room in Kurla and a
    // ₹14,500 room in Goregaon with nothing saying so. Kandivali has exactly one seed listing, so
    // the page is thin and the ladder does fire — but only out to five kilometres, and every row it
    // adds says how far away it is.
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Kandivali", null)))
            .budgetMax(15000)
            .originalQuery("single sharing room in Kandivali under 15k")
            .build();

    SearchDtos.AiSearchResponse res = pipeline.search(intent, null, "test", UUID.randomUUID());

    assertThat(res.homes()).isNotEmpty();
    // Kurla is ~20 km east of Kandivali: outside every tier's ring, so it cannot appear at all
    assertThat(res.homes()).extracting(r -> r.home().localityName()).doesNotContain("Kurla");
    // Goregaon may legitimately fall inside the 5 km ring. What it may never be again is an
    // unlabelled match: if it is on the page it is a near miss that says how far away it is.
    assertThat(res.homes())
        .filteredOn(r -> "Goregaon".equals(r.home().localityName()))
        .allSatisfy(
            r -> {
              assertThat(r.nearMiss()).isTrue();
              assertThat(r.nearMissReason()).contains("min from Kandivali");
            });
    assertThat(res.homes())
        .allSatisfy(
            r -> {
              // within budget exactly, or in the band and labelled as such
              if (r.home().rentMonthly() > 15000) {
                assertThat(r.home().rentMonthly()).isLessThanOrEqualTo(16500);
                assertThat(r.nearMissReason()).contains("over your budget");
              }
              // a row from another suburb is a near miss and says where it is
              if (!"Kandivali".equals(r.home().localityName())) {
                assertThat(r.nearMiss()).isTrue();
                assertThat(r.nearMissReason()).isNotBlank();
              }
            });
    assertThat(res.homes().get(0).home().localityName()).isEqualTo("Kandivali");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aClientSubmittedCommuteToWithNoPlaceText_neverRendersTheLiteralNull() {
    // /apply replays a client-submitted intent straight through the pipeline with no
    // IntentLocalities.resolve pass — CommuteTo.place is a plain nullable String on the wire, so a
    // body carrying only commuteTo.localityId (a chip re-submitting a stored locality id, say) must
    // still degrade to an honest generic label, not the literal word "null" (R7 finding 1).
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .commuteTo(new SearchIntent.CommuteTo(null, SeedLocalities.id("BKC"), 30))
            .originalQuery("flat near work")
            .build();

    SearchDtos.AiSearchResponse res = pipeline.search(intent, null, "test", UUID.randomUUID());

    assertThat(res.homes()).isNotEmpty();
    // commuteLabel/nearMissReason are legitimately null on some rows (no commute measured, or not a
    // near miss) — only their text, when present, must never be the literal word "null".
    for (SearchDtos.AiResult r : res.homes()) {
      if (r.commuteLabel() != null) {
        assertThat(r.commuteLabel()).doesNotContainIgnoringCase("null");
      }
      if (r.nearMissReason() != null) {
        assertThat(r.nearMissReason()).doesNotContainIgnoringCase("null");
      }
      for (MatchScorer.Component c : r.scoreBreakdown()) {
        if (c.detail() != null) {
          assertThat(c.detail()).doesNotContainIgnoringCase("null");
        }
      }
      assertThat(r.matchReasons()).allSatisfy(s -> assertThat(s).doesNotContainIgnoringCase("null"));
      assertThat(r.concerns()).allSatisfy(s -> assertThat(s).doesNotContainIgnoringCase("null"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void raisingTheBudgetSticksForTheRestOfTheSession() {
    // Colaba's only seed listing (₹35,500) is far outside a ₹9,000 cap — the same fixture
    // BudgetChoiceTest and aSearchWithNothingInTheBandStaysEmpty_andOffersACountedRaise rely on to
    // prove a RAISE_BUDGET choice is actually offered here.
    ResponseEntity<Map> first =
        rest.postForEntity("/api/v1/ai/search", json(Map.of("query", "room in colaba under 9000")), Map.class);
    Map<String, Object> firstData = (Map<String, Object>) first.getBody().get("data");
    String freshSessionId = (String) firstData.get("sessionId");
    String freshCookie = first.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
    Map<String, Object> firstIntent = (Map<String, Object>) firstData.get("intent");
    assertThat(firstIntent.get("budgetMax")).isEqualTo(9000);

    List<Map<String, Object>> choices = (List<Map<String, Object>>) firstData.get("choices");
    Map<String, Object> raise =
        choices.stream()
            .filter(c -> "RAISE_BUDGET".equals(c.get("action")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no RAISE_BUDGET choice among " + choices));
    int raisedValue = ((Number) raise.get("value")).intValue();
    assertThat(raisedValue).isGreaterThan(9000);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, freshCookie);

    // clicking the choice: the FULL modified intent, budgetMax patched to the raised value — /apply
    // takes no partial patches, it replays whatever intent the client sends (AiSearchController.apply)
    Map<String, Object> patchedIntent = new java.util.HashMap<>(firstIntent);
    patchedIntent.put("budgetMax", raisedValue);
    ResponseEntity<Map> appliedResp =
        rest.postForEntity(
            "/api/v1/ai/apply",
            new HttpEntity<>(Map.of("sessionId", freshSessionId, "intent", patchedIntent), headers),
            Map.class);
    assertThat(appliedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> appliedData = (Map<String, Object>) appliedResp.getBody().get("data");
    Map<String, Object> appliedIntent = (Map<String, Object>) appliedData.get("intent");
    assertThat(appliedIntent.get("budgetMax")).isEqualTo(raisedValue);

    // sticky: a later refinement that never mentions budget still carries the raised value
    ResponseEntity<Map> refined =
        rest.postForEntity(
            "/api/v1/ai/refine",
            new HttpEntity<>(Map.of("query", "only verified ones", "sessionId", freshSessionId), headers),
            Map.class);
    assertThat(refined.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> refinedData = (Map<String, Object>) refined.getBody().get("data");
    Map<String, Object> refinedIntent = (Map<String, Object>) refinedData.get("intent");
    assertThat(refinedIntent.get("budgetMax")).isEqualTo(raisedValue);
    assertThat(refinedIntent.get("verifiedOnly")).isEqualTo(true);
  }

  // ---- what the API now tells the client about tiers, framing and the city (spec §4.6, §4.11) ----

  @SuppressWarnings("unchecked")
  private Map<String, Object> searchFor(String query) {
    ResponseEntity<Map> response =
        rest.postForEntity("/api/v1/ai/search", json(Map.of("query", query)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return (Map<String, Object>) response.getBody().get("data");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aThinLocalityIsReportedAsSuchRatherThanQuietlyGoingCitywide() {
    // Kandivali holds exactly one seed listing, so "under 15k" there is a thin page the ladder tops
    // up — the client must be able to see that, not have to infer it from a prose note.
    Map<String, Object> data = searchFor("single sharing room in Kandivali under 15k");

    Map<String, Object> summary = (Map<String, Object>) data.get("resultSummary");
    assertThat(summary.get("anchorName")).isEqualTo("Kandivali");
    assertThat(summary.get("headline")).isNotNull();
    assertThat(((Number) summary.get("exactCount")).intValue()).isLessThan(3);

    List<Map<String, Object>> homes = (List<Map<String, Object>>) data.get("homes");
    assertThat(homes).isNotEmpty();
    assertThat(homes).allSatisfy(h -> assertThat(h.get("tier")).isNotNull());
    assertThat(homes).allSatisfy(h -> assertThat(h.get("distanceKm")).isNotNull());
    assertThat(homes).allSatisfy(h -> assertThat(h.get("anchorName")).isEqualTo("Kandivali"));
    assertThat(homes).allSatisfy(h -> assertThat(h.get("minutesFromAnchor")).isNotNull());
    // grouped in ladder order, so the client can render blocks by walking the list once
    List<String> tierOrder = List.of("EXACT", "NEARBY", "OVER_BUDGET");
    for (int i = 1; i < homes.size(); i++) {
      assertThat(tierOrder.indexOf((String) homes.get(i).get("tier")))
          .isGreaterThanOrEqualTo(tierOrder.indexOf((String) homes.get(i - 1).get("tier")));
    }
    // the counts add up to the page, so a client can group by tier without re-deriving them
    int tiered =
        ((Number) summary.get("exactCount")).intValue()
            + ((Number) summary.get("nearbyCount")).intValue()
            + ((Number) summary.get("overBudgetCount")).intValue();
    assertThat(tiered).isEqualTo(homes.size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aUserWithNoProfileLocalityIsToldTheCityIsUnknown() {
    // an anonymous searcher is UNSET too — results still appear, and the prompt says why
    Map<String, Object> data = searchFor("private room under 20k");

    Map<String, Object> citySearch = (Map<String, Object>) data.get("citySearch");
    assertThat(citySearch.get("source")).isEqualTo("UNSET");
    assertThat(citySearch.get("city")).isNull();
    assertThat((String) citySearch.get("prompt")).contains("don't know which city");
    assertThat((List<?>) data.get("homes")).isNotEmpty(); // not an error state
  }

  @Test
  @SuppressWarnings("unchecked")
  void aQueryNamingNoLocalityGetsNoFallbackFraming() {
    // citywide with no headline and no anchor — a different case from an unplaceable name
    Map<String, Object> data = searchFor("private room under 20k");

    Map<String, Object> summary = (Map<String, Object>) data.get("resultSummary");
    assertThat(summary.get("headline")).isNull();
    assertThat(summary.get("anchorName")).isNull();
    assertThat(summary.get("terminus")).isNull();
    assertThat((List<Map<String, Object>>) data.get("homes"))
        .allSatisfy(h -> assertThat(h.get("distanceKm")).isNull());
  }

  private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
