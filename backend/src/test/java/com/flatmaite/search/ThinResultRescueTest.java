package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.RescueLadder.SearchTier;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A thin page is when the product should work hardest, and the only two honest moves are widening
 * the map and saying out loud that a row is over budget. The ladder is deterministic so the same
 * search always rescues the same way; it never fills the page by quietly giving a filter up, and it
 * never trades away a promise.
 */
class ThinResultRescueTest {

  private static final UUID POWAI = UUID.randomUUID();

  private final FlatmaiteProperties.Search props = new FlatmaiteProperties.Search();

  private static Placement placed() {
    return new Placement(List.of(POWAI), 19.1197, 72.9050, Placement.Source.GAZETTEER, 1.0);
  }

  private static SearchIntent inPowai() {
    return SearchIntent.builder()
        .locations(List.of(new LocationRef("Powai", POWAI)))
        .budgetMax(30000)
        .furnished(Furnishing.FULLY_FURNISHED)
        .build();
  }

  // ---- what the ladder offers: distance first, then a labelled band, and nothing else ----

  @Test
  void theWiderRingComesFirst_whenAPlaceWasNamed() {
    List<RescueLadder.Tier> tiers = RescueLadder.tiers(inPowai(), placed(), props);

    assertThat(tiers.get(0).tier()).isEqualTo(SearchTier.EXACT);
    assertThat(tiers.get(0).radiusKm()).isEqualTo(0.0);
    assertThat(tiers.get(1).tier()).isEqualTo(SearchTier.NEARBY);
    assertThat(tiers.get(1).radiusKm()).isEqualTo(props.getNearbyRadiusKm());
  }

  @Test
  void withNoPlaceNamed_theLadderHasNothingToWiden_andGoesStraightToTheBand() {
    SearchIntent intent = SearchIntent.builder().budgetMax(30000).build();

    assertThat(RescueLadder.tiers(intent, Placement.none(), props))
        .extracting(RescueLadder.Tier::tier)
        .containsExactly(SearchTier.EXACT, SearchTier.OVER_BUDGET);
  }

  @Test
  void theWidenedTierStillEnforcesTheBudgetExactly_soAWithinBudgetBlockIsTrue() {
    List<RescueLadder.Tier> tiers = RescueLadder.tiers(inPowai(), placed(), props);

    assertThat(tiers.get(0).intent().budgetMax()).isEqualTo(30000);
    assertThat(tiers.get(1).intent().budgetMax()).isEqualTo(30000);
    // only the tier whose own heading says "over budget" carries the +10% band
    assertThat(tiers.get(2).tier()).isEqualTo(SearchTier.OVER_BUDGET);
    assertThat(tiers.get(2).intent().budgetMax()).isEqualTo(33000);
  }

  @Test
  void noTierDropsAFilter_howeverLittleConfidenceItHas() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", POWAI)))
            .budgetMax(30000)
            .furnished(Furnishing.FULLY_FURNISHED)
            .roomType(RoomType.ENTIRE)
            .confidence(Map.of("budgetMax", 1.0, "furnished", 0.8, "roomType", 0.8))
            .build();

    // the old ladder dropped these one at a time, least-confident first; this one never does
    assertThat(RescueLadder.tiers(intent, placed(), props))
        .allSatisfy(
            t -> {
              assertThat(t.intent().furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
              assertThat(t.intent().roomType()).isEqualTo(RoomType.ENTIRE);
              assertThat(t.intent().locations()).isEqualTo(intent.locations());
            });
  }

  @Test
  void promisesSurviveEveryTier() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", POWAI)))
            .excludeLocations(List.of(new LocationRef("Kurla", UUID.randomUUID())))
            .verifiedOnly(true)
            .budgetMax(30000)
            .build();

    assertThat(RescueLadder.tiers(intent, placed(), props))
        .allSatisfy(
            t -> {
              assertThat(t.intent().excludeLocations()).isEqualTo(intent.excludeLocations());
              assertThat(t.intent().verifiedOnly()).isTrue();
            });
  }

  @Test
  void anIntentWithNoPlaceAndNoBudget_hasOnlyTheExactTier() {
    assertThat(RescueLadder.tiers(SearchIntent.builder().build(), Placement.none(), props))
        .extracting(RescueLadder.Tier::tier)
        .containsExactly(SearchTier.EXACT);
  }

  // ---- what the rows say ----

  @Test
  void aWiderRingReasonNeverClaimsADistanceItDoesNotHave() {
    String unresolved =
        SearchPipeline.nearMissReason(
            SearchTier.NEARBY, 21000, null, "your area", false, SearchIntent.builder().build());

    assertThat(unresolved).isEqualTo("Outside your preferred areas");
    assertThat(unresolved).doesNotContain("null");
  }

  @Test
  void aWiderRingReasonReadsToForACommuteAndFromForAHomeArea() {
    SearchIntent empty = SearchIntent.builder().build();

    assertThat(SearchPipeline.nearMissReason(SearchTier.NEARBY, 21000, 12, "BKC", true, empty))
        .isEqualTo("~12 min to BKC");
    assertThat(SearchPipeline.nearMissReason(SearchTier.NEARBY, 21000, 38, "Goregaon", false, empty))
        .isEqualTo("~38 min from Goregaon");
  }

  @Test
  void anOverBudgetRowSaysHowFarOverBudgetItIs_notHowFarAwayItIs() {
    SearchIntent intent = SearchIntent.builder().budgetMax(15000).build();

    assertThat(SearchPipeline.nearMissReason(SearchTier.OVER_BUDGET, 16500, 12, "Kandivali", false, intent))
        .isEqualTo("₹16,500 — ₹1,500 over your budget");
  }

  @Test
  void anOverBudgetRowInventsNoFigureWhenTheArithmeticIsNotThere() {
    SearchIntent noBudget = SearchIntent.builder().build();

    assertThat(SearchPipeline.nearMissReason(SearchTier.OVER_BUDGET, 16500, null, "your area", false, noBudget))
        .isEqualTo("Slightly over your budget");
    assertThat(
            SearchPipeline.nearMissReason(
                SearchTier.OVER_BUDGET, null, null, "your area", false, SearchIntent.builder().budgetMax(15000).build()))
        .isEqualTo("Slightly over your budget");
  }

  @Test
  void aNearMissReasonNeverNamesAFilterTheUserDidNotGiveUp() {
    SearchIntent intent =
        SearchIntent.builder().roomType(RoomType.ENTIRE).budgetMax(15000).build();

    // the old ladder said "room type — you asked for entire"; nothing may say that any more,
    // because nothing drops a slot without the user clicking a relaxer
    assertThat(SearchPipeline.nearMissReason(SearchTier.NEARBY, 14000, 20, "Powai", false, intent))
        .doesNotContain("room type")
        .doesNotContain("you asked for");
    assertThat(SearchPipeline.nearMissReason(SearchTier.OVER_BUDGET, 16000, 20, "Powai", false, intent))
        .doesNotContain("room type");
  }

  // ---- walking the ladder: the control logic, without a database ----

  private static final SearchIntent ANY = SearchIntent.builder().build();

  private static RescueLadder.Tier tier(SearchTier tier, double radiusKm) {
    return new RescueLadder.Tier(tier, ANY, radiusKm);
  }

  private static HybridRetriever.Candidate candidate(UUID id) {
    return new HybridRetriever.Candidate(id, null, null, null, HybridRetriever.Retrieval.NONE);
  }

  /** A tier that yields the listings named, by id. */
  private static List<HybridRetriever.Candidate> found(UUID... ids) {
    return Arrays.stream(ids).map(ThinResultRescueTest::candidate).toList();
  }

  @Test
  void aTierThatFindsNothingNew_isSkippedAndNeverNamedInTheSummary() {
    UUID already = UUID.randomUUID();
    UUID fresh = UUID.randomUUID();
    List<RescueLadder.Tier> tiers =
        List.of(tier(SearchTier.NEARBY, 5.0), tier(SearchTier.OVER_BUDGET, 5.0));

    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            tiers,
            Set.of(already),
            6,
            (t, radius) -> t.tier() == SearchTier.NEARBY ? found(already) : found(fresh));

    // the nearby tier re-found only what we already had, so it contributed nothing and is not named
    assertThat(walk.reasons()).containsExactly("slightly over budget");
    assertThat(walk.added()).containsOnlyKeys(fresh);
    assertThat(walk.tierOf()).containsEntry(fresh, SearchTier.OVER_BUDGET);
  }

  @Test
  void theWalkStopsAsSoonAsThePageIsNoLongerThin() {
    List<UUID> ids = Stream.generate(UUID::randomUUID).limit(6).toList();
    List<RescueLadder.Tier> tiers =
        List.of(tier(SearchTier.NEARBY, 5.0), tier(SearchTier.OVER_BUDGET, 5.0));
    List<SearchTier> asked = new ArrayList<>();

    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            tiers,
            Set.of(ids.get(0), ids.get(1)),
            4,
            (t, radius) -> {
              asked.add(t.tier());
              return found(ids.get(2), ids.get(3), ids.get(4));
            });

    // the nearby tier took the count from 2 to 5, past the minimum of 4 — the band is never tried,
    // so nobody is shown a listing over their budget to fill a page that was already full enough
    assertThat(asked).containsExactly(SearchTier.NEARBY);
    assertThat(walk.reasons()).containsExactly("further out");
    assertThat(walk.widerRingRadiusKm()).isEqualTo(5.0);
  }

  @Test
  void anExhaustedLadderReturnsWhatItHas_ratherThanFailing() {
    UUID already = UUID.randomUUID();
    UUID one = UUID.randomUUID();
    List<RescueLadder.Tier> tiers =
        List.of(tier(SearchTier.NEARBY, 5.0), tier(SearchTier.OVER_BUDGET, 5.0));

    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            tiers, Set.of(already), 6, (t, radius) -> t.tier() == SearchTier.NEARBY ? found(one) : found());

    // both tiers were walked, the page is still short of 6, and the one find survives
    assertThat(walk.added()).containsOnlyKeys(one);
    assertThat(walk.reasons()).containsExactly("further out");
  }

  @Test
  void eachTierRetrievesAtItsOwnStatedRadius() {
    List<Double> radii = new ArrayList<>();

    SearchPipeline.walkLadder(
        List.of(tier(SearchTier.NEARBY, 5.0), tier(SearchTier.OVER_BUDGET, 2.0)),
        Set.of(),
        99,
        (t, radius) -> {
          radii.add(radius);
          return found(UUID.randomUUID());
        });

    assertThat(radii).containsExactly(5.0, 2.0);
  }

  @Test
  void theNotedRadiusIsTheWidestRingThatActuallyContributed() {
    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            List.of(tier(SearchTier.NEARBY, 5.0), tier(SearchTier.OVER_BUDGET, 0.0)),
            Set.of(),
            99,
            (t, radius) -> found(UUID.randomUUID()));

    assertThat(walk.widerRingRadiusKm()).isEqualTo(5.0);
  }

  @Test
  void aBandThatNeverLeftTheRequestedAreaClaimsNoRing() {
    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(
            List.of(tier(SearchTier.OVER_BUDGET, 0.0)),
            Set.of(),
            99,
            (t, radius) -> found(UUID.randomUUID()));

    assertThat(walk.reasons()).containsExactly("slightly over budget");
    assertThat(walk.widerRingRadiusKm()).isNull();
  }

  @Test
  void anEmptyLadderAddsNothing() {
    SearchPipeline.Rescue walk =
        SearchPipeline.walkLadder(List.of(), Set.of(), 6, (t, radius) -> found(UUID.randomUUID()));

    assertThat(walk.added()).isEmpty();
    assertThat(walk.reasons()).isEmpty();
    assertThat(walk.widerRingRadiusKm()).isNull();
  }

  @Test
  void theWalkNeverMutatesThePageItWasHanded() {
    UUID already = UUID.randomUUID();
    Set<UUID> exactPage = new LinkedHashSet<>(Set.of(already));

    SearchPipeline.walkLadder(
        List.of(tier(SearchTier.NEARBY, 5.0)), exactPage, 6, (t, radius) -> found(UUID.randomUUID()));

    assertThat(exactPage).containsExactly(already);
  }
}
