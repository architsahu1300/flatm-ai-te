package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A thin page is when the product should work hardest. The ladder is deterministic so the same
 * search always rescues the same way, and it never trades away a promise to fill the page.
 */
class ThinResultRescueTest {

  private static final UUID POWAI = UUID.randomUUID();

  @Test
  void theWiderRingComesFirst_whenAPlaceWasNamed() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", POWAI)))
            .budgetMax(30000)
            .furnished(Furnishing.FULLY_FURNISHED)
            .build();

    List<RescueLadder.Rung> rungs = RescueLadder.rungs(intent, 45);

    assertThat(rungs.get(0).slot()).isNull();
    assertThat(rungs.get(0).radiusMinutes()).isEqualTo(45);
  }

  @Test
  void withNoPlaceNamed_theLadderStartsByDroppingAFilter() {
    SearchIntent intent = SearchIntent.builder().budgetMax(30000).build();
    assertThat(RescueLadder.rungs(intent, 45).get(0).slot()).isEqualTo("budgetMax");
  }

  @Test
  void filtersAreDroppedLeastConfidentFirst_tiesInSlotOrder() {
    SearchIntent intent =
        SearchIntent.builder()
            .budgetMax(30000)
            .furnished(Furnishing.FULLY_FURNISHED)
            .roomType(RoomType.ENTIRE)
            .confidence(Map.of("budgetMax", 1.0, "furnished", 0.8, "roomType", 0.8))
            .build();

    assertThat(RescueLadder.rungs(intent, 45))
        .extracting(RescueLadder.Rung::slot)
        .containsExactly("roomType", "furnished", "budgetMax"); // 0.8 ties break in GATED_SLOTS order
  }

  @Test
  void promisesAreNeverInTheLadder() {
    SearchIntent intent =
        SearchIntent.builder()
            .excludeLocations(List.of(new LocationRef("Powai", POWAI)))
            .verifiedOnly(true)
            .budgetMax(30000)
            .build();
    assertThat(RescueLadder.rungs(intent, 45))
        .extracting(RescueLadder.Rung::slot)
        .containsExactly("budgetMax");
  }

  @Test
  void aSoftSlotIsNotInTheLadder_itIsAlreadyNotFiltering() {
    SearchIntent intent =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .budgetMax(30000)
            .confidence(Map.of("roomType", 0.5))
            .build();
    assertThat(RescueLadder.rungs(intent, 45))
        .extracting(RescueLadder.Rung::slot)
        .containsExactly("budgetMax");
  }

  @Test
  void eachRungsIntentDropsExactlyItsOwnSlot() {
    SearchIntent intent =
        SearchIntent.builder().budgetMax(30000).furnished(Furnishing.FULLY_FURNISHED).build();
    RescueLadder.Rung budget =
        RescueLadder.rungs(intent, 45).stream().filter(r -> "budgetMax".equals(r.slot())).findFirst().orElseThrow();

    assertThat(budget.intent().budgetMax()).isNull();
    assertThat(budget.intent().furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
  }

  @Test
  void anIntentWithNothingToRelax_hasNoLadder() {
    assertThat(RescueLadder.rungs(SearchIntent.builder().build(), 45)).isEmpty();
  }

  @Test
  void aWiderRingReasonNeverClaimsADistanceItDoesNotHave() {
    RescueLadder.Rung ring = new RescueLadder.Rung(null, SearchIntent.builder().build(), 45, "further out");

    String unresolved =
        SearchPipeline.nearMissReason(ring, null, "your area", false, SearchIntent.builder().build());

    assertThat(unresolved).isEqualTo("Outside your preferred areas");
    assertThat(unresolved).doesNotContain("null");
  }

  @Test
  void aWiderRingReasonReadsToForACommuteAndFromForAHomeArea() {
    RescueLadder.Rung ring = new RescueLadder.Rung(null, SearchIntent.builder().build(), 45, "further out");
    SearchIntent empty = SearchIntent.builder().build();

    assertThat(SearchPipeline.nearMissReason(ring, 12, "BKC", true, empty)).isEqualTo("~12 min to BKC");
    assertThat(SearchPipeline.nearMissReason(ring, 38, "Goregaon", false, empty)).isEqualTo("~38 min from Goregaon");
  }

  @Test
  void aDroppedSlotReasonNamesTheSlotAndTheValueTheUserAskedFor() {
    SearchIntent intent = SearchIntent.builder().roomType(RoomType.ENTIRE).build();
    RescueLadder.Rung rung = new RescueLadder.Rung("roomType", intent, null, "room type");

    assertThat(SearchPipeline.nearMissReason(rung, null, "your area", false, intent))
        .contains("room type")
        .contains("you asked for");
  }

  @Test
  void aDroppedLifestyleReasonNamesTheFacetsThatWereSet() {
    SearchIntent intent =
        SearchIntent.builder()
            .lifestyle(SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").diet("VEGETARIAN").build())
            .build();
    RescueLadder.Rung rung = new RescueLadder.Rung("lifestyle", intent, null, "lifestyle");

    String reason = SearchPipeline.nearMissReason(rung, null, "your area", false, intent);

    assertThat(reason).contains("no smokers").contains("vegetarian");
    assertThat(reason).doesNotContain("your lifestyle preferences");
  }

  // ---- walking the ladder: the control logic, without a database ----

  private static final SearchIntent ANY = SearchIntent.builder().build();

  private static RescueLadder.Rung rung(String slot, Integer radius, String reason) {
    return new RescueLadder.Rung(slot, ANY, radius, reason);
  }

  private static HybridRetriever.Candidate candidate(UUID id) {
    return new HybridRetriever.Candidate(id, null, null, null, HybridRetriever.Retrieval.NONE);
  }

  /** A rung that yields the listings named, by id. */
  private static List<HybridRetriever.Candidate> found(UUID... ids) {
    return java.util.Arrays.stream(ids).map(ThinResultRescueTest::candidate).toList();
  }

  @Test
  void aRungThatFindsNothingNew_isSkippedAndNeverNamedInTheSummary() {
    UUID already = UUID.randomUUID();
    UUID fresh = UUID.randomUUID();
    List<RescueLadder.Rung> rungs =
        List.of(rung(null, 45, "further out"), rung("budgetMax", null, "budget"));

    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            rungs,
            Set.of(already),
            6,
            25,
            (r, radius) -> "further out".equals(r.reason()) ? found(already) : found(fresh));

    // rung 1 re-found only what we already had, so it contributed nothing and is not a reason
    assertThat(walk.reasons()).containsExactly("budget");
    assertThat(walk.added()).containsOnlyKeys(fresh);
    // and the rung index still counts every rung walked, so the second rung is rung 2
    assertThat(walk.rungOf()).containsEntry(fresh, 2);
    assertThat(walk.widerRingRadiusMinutes()).isNull();
  }

  @Test
  void theWalkStopsAsSoonAsThePageIsNoLongerThin() {
    List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(6).toList();
    List<RescueLadder.Rung> rungs =
        List.of(rung(null, 45, "further out"), rung("budgetMax", null, "budget"));
    List<String> asked = new java.util.ArrayList<>();

    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            rungs,
            Set.of(ids.get(0), ids.get(1)),
            4,
            25,
            (r, radius) -> {
              asked.add(r.reason());
              return found(ids.get(2), ids.get(3), ids.get(4));
            });

    // the first rung took the count from 2 to 5, past the minimum of 4 — the second is never tried
    assertThat(asked).containsExactly("further out");
    assertThat(walk.reasons()).containsExactly("further out");
    assertThat(walk.widerRingRadiusMinutes()).isEqualTo(45);
  }

  @Test
  void anExhaustedLadderReturnsWhatItHas_ratherThanFailing() {
    UUID already = UUID.randomUUID();
    UUID one = UUID.randomUUID();
    List<RescueLadder.Rung> rungs = List.of(rung("budgetMax", null, "budget"), rung("roomType", null, "room type"));

    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            rungs, Set.of(already), 6, 25, (r, radius) -> "budget".equals(r.reason()) ? found(one) : found());

    // both rungs were walked, the page is still short of 6, and the one find survives
    assertThat(walk.added()).containsOnlyKeys(one);
    assertThat(walk.reasons()).containsExactly("budget");
  }

  @Test
  void aRungWithNoRadiusOfItsOwn_retrievesAtTheConfiguredNearbyRadius() {
    List<Integer> radii = new java.util.ArrayList<>();

    SearchPipeline.walkLadder(
        List.of(rung(null, 45, "further out"), rung("budgetMax", null, "budget")),
        Set.of(),
        99,
        25,
        (r, radius) -> {
          radii.add(radius);
          return found(UUID.randomUUID());
        });

    assertThat(radii).containsExactly(45, 25);
  }

  @Test
  void anEmptyLadderAddsNothing() {
    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(List.of(), Set.of(), 6, 25, (r, radius) -> found(UUID.randomUUID()));

    assertThat(walk.added()).isEmpty();
    assertThat(walk.reasons()).isEmpty();
    assertThat(walk.widerRingRadiusMinutes()).isNull();
  }
}
