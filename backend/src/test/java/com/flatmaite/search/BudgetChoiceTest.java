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
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired HybridRetriever retriever;
  @Autowired JdbcTemplate jdbc;

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
    AiSearchResponse raisedResponse = pipeline.search(raised, null, "test", UUID.randomUUID(), null, false, CityScope.unset());
    assertThat(raisedResponse.homes()).isNotEmpty();
  }

  /**
   * Borivali, not Colaba, because this is the case that can tell the two countings apart. Colaba's
   * 5 km ring holds no <em>active</em> seed listing but its own, so counting it widened and
   * counting it strictly give the same answer and any assertion about it passes either way.
   * Borivali (₹18,000) has Kandivali (₹13,000, ~2.9 km) and Dahisar (₹13,500, ~2.2 km) inside its
   * ring, so a widened count reports a price Borivali itself does not offer.
   */
  private SearchIntent inBorivaliUnder(int budgetMax) {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .locations(List.of(new SearchIntent.LocationRef("Borivali", null)))
        .budgetMax(budgetMax)
        .originalQuery("room in borivali under %d".formatted(budgetMax))
        .build();
  }

  private Integer cheapestActiveIn(String locality) {
    return jdbc.queryForObject(
        """
        SELECT min(l.rent_monthly)
        FROM listings l JOIN properties p ON p.id = l.property_id
        WHERE l.deleted_at IS NULL AND l.status = 'ACTIVE' AND p.locality_id = ?
        """,
        Integer.class,
        SeedLocalities.id(locality));
  }

  @Test
  void theChoicesCountAndPriceMatchAnIndependentQueryForTheSameCriteria() {
    // Spec §5. theChoiceNamesTheCheapestPriceThatActuallyOpensListings above compares the label
    // against the choice's own fields, so it is true by construction and cannot see whose listings
    // `count` is actually describing. This counts the chip's own sentence — "in Borivali, at or
    // under ₹X" — in raw SQL that shares nothing with the production filter builder.
    SearchIntent intent = inBorivaliUnder(12000);

    // the guard that stops this test from quietly becoming true by construction too: the ring
    // around Borivali must really hold something cheaper than Borivali, or a widened count and a
    // strict one would agree and prove nothing
    assertThat(retriever.admittedLocalityIds(intent, 5.0)).contains(SeedLocalities.id("Kandivali"));
    assertThat(cheapestActiveIn("Kandivali")).isLessThan(cheapestActiveIn("Borivali"));

    Choice choice = pipeline.budgetChoice(intent, pipeline.placementOf(intent)).orElseThrow();

    // the price named is the cheapest rent *in Borivali*, rounded up to ₹500 — not the cheapest
    // within 5 km of it
    int expectedValue = (int) (Math.ceil(cheapestActiveIn("Borivali") / 500.0) * 500);
    assertThat(choice.value()).isEqualTo(expectedValue);

    Long independentCount =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM listings l JOIN properties p ON p.id = l.property_id
            WHERE l.deleted_at IS NULL AND l.status = 'ACTIVE'
              AND p.locality_id = ? AND l.rent_monthly <= ?
            """,
            Long.class,
            SeedLocalities.id("Borivali"),
            choice.value());

    assertThat(choice.count()).isEqualTo(independentCount);
    assertThat(choice.label()).isEqualTo("Borivali has %d from ₹%,d".formatted(independentCount, expectedValue));
  }

  @Test
  void theChoicesOwnNextScreenAgreesWithIt() {
    // The failure this replaces: a chip counted through the widened ring — "Borivali has 2 from
    // ₹13,000", paid for by Kandivali and Dahisar — clicked, landing on a page headlined "No
    // listings in Borivali under ₹13,000", because the EXACT tier is the requested placement only.
    // A chip that moves the user's stated budget must be a claim its own next page can stand behind.
    SearchIntent intent = inBorivaliUnder(12000);
    Choice choice = pipeline.budgetChoice(intent, pipeline.placementOf(intent)).orElseThrow();

    // clicking it is an /apply with the new budget, and that re-run is the escalated one (§4.7)
    AiSearchResponse next =
        pipeline.search(
            intent.toBuilder().budgetMax(choice.value()).build(),
            null,
            "test",
            UUID.randomUUID(),
            null,
            true,
            CityScope.unset());

    assertThat(next.resultSummary().anchorName()).isEqualTo("Borivali");
    assertThat(next.resultSummary().headline()).doesNotContain("No listings in Borivali");
    // the block the chip named is not empty, and holds no more than the chip promised
    assertThat(next.resultSummary().exactCount()).isGreaterThan(0);
    assertThat((long) next.resultSummary().exactCount()).isLessThanOrEqualTo(choice.count());
    assertThat(next.homes())
        .filteredOn(r -> r.tier() == RescueLadder.SearchTier.EXACT)
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.home().rentMonthly()).isLessThanOrEqualTo(choice.value()));
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

    AiSearchResponse response = pipeline.search(intent, null, "test", UUID.randomUUID(), null, false, CityScope.unset());

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

    AiSearchResponse ordinary = pipeline.search(intent, null, "test", UUID.randomUUID(), null, false, CityScope.unset());
    AiSearchResponse escalated = pipeline.search(intent, null, "test", UUID.randomUUID(), null, true, CityScope.unset());

    assertThat(ordinary.note()).contains("~5.0 km");
    assertThat(escalated.note()).contains("~20.0 km");
  }
}
