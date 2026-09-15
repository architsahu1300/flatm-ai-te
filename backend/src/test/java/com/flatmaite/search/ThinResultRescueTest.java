package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
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
}
