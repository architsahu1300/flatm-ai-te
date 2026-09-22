package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.RescueLadder.SearchTier;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The ladder relaxes exactly two things and labels both: distance first, then a +10% budget band.
 * Everything else the reader extracted stays enforced in every tier — giving one of those up is a
 * thing the user does by clicking a relaxer, never something the system does behind their back.
 */
class FallbackLadderTest {

  private static final UUID KANDIVALI = UUID.randomUUID();

  private final FlatmaiteProperties.Search props = new FlatmaiteProperties.Search();

  @Test
  void theLadderWidensBeforeItEverTouchesTheBudget() {
    SearchIntent intent = kandivaliUnder(15000);

    List<RescueLadder.Tier> tiers = RescueLadder.tiers(intent, kandivaliPlacement(), props);

    assertThat(tiers)
        .extracting(RescueLadder.Tier::tier)
        .containsExactly(SearchTier.EXACT, SearchTier.NEARBY, SearchTier.OVER_BUDGET);
    assertThat(tiers.get(0).radiusKm()).isEqualTo(0.0);
    assertThat(tiers.get(1).radiusKm()).isEqualTo(5.0);
  }

  @Test
  void theUnderBudgetTiersEnforceTheBudgetExactly() {
    List<RescueLadder.Tier> tiers = RescueLadder.tiers(kandivaliUnder(15000), kandivaliPlacement(), props);

    assertThat(tiers.get(0).intent().budgetMax()).isEqualTo(15000);
    assertThat(tiers.get(1).intent().budgetMax()).isEqualTo(15000);
    assertThat(tiers.get(2).intent().budgetMax()).isEqualTo(16500); // +10%, labelled
  }

  @Test
  void theOverBudgetTierStaysWithinTheNearbyRing_ratherThanGoingCitywide() {
    List<RescueLadder.Tier> tiers = RescueLadder.tiers(kandivaliUnder(15000), kandivaliPlacement(), props);

    assertThat(tiers.get(2).radiusKm()).isEqualTo(props.getNearbyRadiusKm());
  }

  @Test
  void everyTierKeepsEveryOtherFilter() {
    SearchIntent intent =
        kandivaliUnder(15000).toBuilder()
            .roomType(RoomType.PRIVATE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .genderPreference(com.flatmaite.common.domain.GenderPreference.FEMALE_ONLY)
            .amenities(List.of("wifi"))
            .excludeLocations(List.of(new LocationRef("Kurla", UUID.randomUUID())))
            .build();

    assertThat(RescueLadder.tiers(intent, kandivaliPlacement(), props))
        .allSatisfy(
            t -> {
              assertThat(t.intent().roomType()).isEqualTo(RoomType.PRIVATE);
              assertThat(t.intent().furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
              assertThat(t.intent().genderPreference())
                  .isEqualTo(com.flatmaite.common.domain.GenderPreference.FEMALE_ONLY);
              assertThat(t.intent().amenities()).containsExactly("wifi");
              assertThat(t.intent().excludeLocations()).isEqualTo(intent.excludeLocations());
              assertThat(t.intent().locations()).isEqualTo(intent.locations());
            });
  }

  @Test
  void aQueryWithNoPlaceHasNoDistanceTiers() {
    SearchIntent intent = SearchIntent.builder().budgetMax(15000).build();

    List<RescueLadder.Tier> tiers = RescueLadder.tiers(intent, Placement.none(), props);

    assertThat(tiers).extracting(RescueLadder.Tier::tier).containsExactly(SearchTier.EXACT, SearchTier.OVER_BUDGET);
    // nothing to widen around, so the band is not dressed up as a ring either
    assertThat(tiers.get(1).radiusKm()).isEqualTo(0.0);
  }

  @Test
  void aQueryWithNoBudgetHasNoOverBudgetTier() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of(new LocationRef("Kandivali", KANDIVALI))).build();

    assertThat(RescueLadder.tiers(intent, kandivaliPlacement(), props))
        .extracting(RescueLadder.Tier::tier)
        .containsExactly(SearchTier.EXACT, SearchTier.NEARBY);
  }

  @Test
  void noTierEverDropsASlotTheWayTheOldLadderDid() {
    SearchIntent intent = kandivaliUnder(15000).toBuilder().roomType(RoomType.PRIVATE).build();

    // the old ladder's later rungs cleared slots outright; nothing here may
    assertThat(RescueLadder.tiers(intent, kandivaliPlacement(), props))
        .noneSatisfy(t -> assertThat(t.intent().roomType()).isNull());
  }

  @Test
  void theBandIsBuiltOnACopy_soTheUsersOwnIntentIsNeverRewritten() {
    SearchIntent intent = kandivaliUnder(15000);

    List<RescueLadder.Tier> tiers = RescueLadder.tiers(intent, kandivaliPlacement(), props);

    // tiers are retrieval concerns (spec §4.5): the two under-budget tiers hand back the very
    // intent they were given, and only the band is a new object — so a later refinement
    // ("only verified ones") still carries ₹15,000 and not ₹16,500
    assertThat(tiers.get(0).intent()).isSameAs(intent);
    assertThat(tiers.get(1).intent()).isSameAs(intent);
    assertThat(tiers.get(2).intent()).isNotSameAs(intent);
    assertThat(intent.budgetMax()).isEqualTo(15000);
  }

  @Test
  void aLowConfidenceSlotIsStillCarriedByEveryTier_theGateDecidesWhetherItFilters() {
    SearchIntent intent =
        kandivaliUnder(15000).toBuilder()
            .roomType(RoomType.PRIVATE)
            .confidence(java.util.Map.of("roomType", 0.4))
            .build();

    assertThat(RescueLadder.tiers(intent, kandivaliPlacement(), props))
        .allSatisfy(
            t -> {
              assertThat(t.intent().roomType()).isEqualTo(RoomType.PRIVATE);
              assertThat(t.intent().confidenceOf("roomType")).isEqualTo(0.4);
            });
  }

  private static SearchIntent kandivaliUnder(int budgetMax) {
    return SearchIntent.builder()
        .locations(List.of(new LocationRef("Kandivali", KANDIVALI)))
        .budgetMax(budgetMax)
        .build();
  }

  private static Placement kandivaliPlacement() {
    return new Placement(List.of(KANDIVALI), 19.2045, 72.8519, Placement.Source.GAZETTEER, 1.0);
  }
}
