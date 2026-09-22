package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.search.SearchDtos.AiSearchResponse;
import com.flatmaite.search.SearchDtos.Choice;
import com.flatmaite.search.SearchDtos.ChoiceAction;
import com.flatmaite.seed.SeedLocalities;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The counted "Raise budget" choice (spec §4.6) and the one thing that must never happen to it:
 * the user's stated {@code budgetMax} does not move until they click it. Auto-shown over-budget
 * rows, and viewing or scoring them, are not consent — only a posted {@code RAISE_BUDGET} choice
 * is (spec §4.4 point 2, product owner's ruling).
 *
 * <p>{@code escalation-radius-km} is overridden here to a value distinct from the default {@code
 * nearby-radius-km} (both default to 5.0) so the ring the escalated re-run actually uses can be
 * told apart from the ordinary nearby ring, rather than assumed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.main.web-application-type=servlet")
@TestPropertySource(properties = "flatmaite.search.escalation-radius-km=20.0")
@Testcontainers
@ActiveProfiles("seed")
class BudgetChoiceTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired SearchPipeline pipeline;

  @Test
  void theChoiceNamesTheCheapestPriceThatActuallyOpensListings() {
    // Colaba's only seed listing costs ₹35,500 — the same fixture
    // SearchPipelineIntegrationTest#aSearchWithNothingInTheBandStaysEmpty_andOffersACountedRaise
    // relies on to show ₹9,000 reaches nothing there or nearby, band included. That makes raising
    // the budget a real compromise here, not a no-op — the exact price and count come from the
    // seed itself rather than being guessed at.
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(9000)
            .originalQuery("flat in colaba under 9k")
            .build();

    Choice choice = pipeline.budgetChoice(intent, pipeline.placementOf(intent)).orElseThrow();

    assertThat(choice.action()).isEqualTo(ChoiceAction.RAISE_BUDGET);
    assertThat(choice.value()).isGreaterThan(9000);
    // rounded up to the nearest ₹500, per spec §4.6
    assertThat(choice.value() % 500).isEqualTo(0);
    assertThat(choice.count()).isGreaterThan(0);
    assertThat(choice.label()).isEqualTo("Colaba has %d from ₹%,d".formatted(choice.count(), choice.value()));

    // raising the budget to exactly this value must actually open the page up — not "some", a real
    // non-empty result
    SearchIntent raised = intent.toBuilder().budgetMax(choice.value()).build();
    AiSearchResponse raisedResponse = pipeline.search(raised, null, "test", UUID.randomUUID());
    assertThat(raisedResponse.homes()).isNotEmpty();
  }

  @Test
  void noChoiceIsOfferedWhenRaisingTheBudgetWouldOpenNothing() {
    // nothing in or near Colaba costs anywhere close to this — raising the budget to whatever the
    // cheapest listing costs would already be true today, so there is no compromise to offer
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(500_000)
            .originalQuery("flat in colaba under 500000")
            .build();

    assertThat(pipeline.budgetChoice(intent, pipeline.placementOf(intent))).isEmpty();
  }

  @Test
  void noChoiceIsOfferedWithoutAPlacedLocality() {
    // nothing named, nowhere to anchor "X has N from ₹Y" — the sentence would be a claim about a
    // place the search never named
    SearchIntent intent =
        SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).budgetMax(15000).build();

    assertThat(pipeline.budgetChoice(intent, Placement.none())).isEmpty();
  }

  @Test
  void anAutoShownOverBudgetRowNeverRewritesTheUsersBudget() {
    // Colaba's only listing (₹35,500) survives a ₹33,000 cap only via the labelled +10% band
    // (SearchPipelineIntegrationTest#anOverTightSearchIsToppedUpWithMarkedNearMisses already
    // proves this page comes back non-empty). R2: AiResult.tier() does not exist until Task 9, so
    // the smuggled-in over-budget row is proven by its own rent, read straight off the row, never
    // by a tier label.
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Colaba", null)))
            .budgetMax(33000)
            .originalQuery("flat in colaba under 33000")
            .build();

    AiSearchResponse response = pipeline.search(intent, null, "test", UUID.randomUUID());

    // the page may contain a row over the stated budget...
    assertThat(response.homes()).anySatisfy(r -> assertThat(r.home().rentMonthly()).isGreaterThan(33000));
    // ...but the intent that produced it never moved. Viewing or ranking that row is not consent —
    // only a posted RAISE_BUDGET choice is (product owner's ruling).
    assertThat(response.intent().budgetMax()).isEqualTo(33000);
  }

  @Test
  void theEscalatedReRunReachesFartherThanTheOrdinaryNearbyRing() {
    // Kandivali has exactly one active seed listing (SearchPipelineIntegrationTest's Kandivali
    // tests), always thinner than minResults, so the ladder's distance tier always fires and its
    // radius always makes it into the note. With a budget wide enough that nothing is filtered on
    // price, the only thing that can differ between the two calls below is which ring config fed
    // the ladder: nearby-radius-km (default 5.0, untouched here) or this class's
    // escalation-radius-km override (20.0).
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new SearchIntent.LocationRef("Kandivali", SeedLocalities.id("Kandivali"))))
            .budgetMax(500_000)
            .originalQuery("flat in kandivali")
            .build();

    AiSearchResponse ordinary = pipeline.search(intent, null, "test", UUID.randomUUID(), null, false);
    AiSearchResponse escalated = pipeline.search(intent, null, "test", UUID.randomUUID(), null, true);

    assertThat(ordinary.note()).contains("~5.0 km");
    assertThat(escalated.note()).contains("~20.0 km");
  }
}
