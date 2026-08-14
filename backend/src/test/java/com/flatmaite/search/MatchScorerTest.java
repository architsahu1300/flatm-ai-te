package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.listing.Listing;
import com.flatmaite.search.MatchScorer.ListingCandidate;
import com.flatmaite.search.SearchIntent.Lifestyle;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchScorerTest {

  private Listing listing(int rent, SocialStyle social, Boolean smoking) {
    Listing l =
        Listing.builder()
            .listerId(UUID.randomUUID())
            .type(ListingType.PRIVATE_ROOM)
            .roomType(RoomType.PRIVATE)
            .title("Room")
            .rentMonthly(rent)
            .availableFrom(LocalDate.now().plusDays(10))
            .householdSocial(social)
            .householdSmoking(smoking)
            .qualityScore(0.8f)
            .build();
    l.setUpdatedAt(Instant.now());
    return l;
  }

  private SearchIntent intent(Integer budgetMax, Boolean quiet, String smoking) {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .budgetMax(budgetMax)
        .locations(List.of(new LocationRef("BKC", UUID.randomUUID())))
        .lifestyle(Lifestyle.builder().quiet(quiet).smoking(smoking).build())
        .build();
  }

  private ListingCandidate candidate(Listing l, boolean preferred, Integer commute, Double sim) {
    return new ListingCandidate(
        l, UUID.randomUUID(), "BKC", true, true, true, sim, commute, preferred);
  }

  @Test
  void perfectMatch_scoresHigh_withPositiveDetails() {
    SearchIntent intent = intent(25000, true, "NO_SMOKERS");
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(intent, candidate(listing(22000, SocialStyle.QUIET, false), true, null, 0.7));

    assertThat(scored.matchScore()).isGreaterThanOrEqualTo(85);
    assertThat(MatchScorer.positiveDetails(scored))
        .anySatisfy(d -> assertThat(d).contains("under your"))
        .anySatisfy(d -> assertThat(d).contains("Quiet household"));
    assertThat(MatchScorer.concernDetails(scored)).isEmpty();
  }

  @Test
  void overBudget_and_partyFlat_scoreLow_withConcerns() {
    SearchIntent intent = intent(20000, true, "NO_SMOKERS");
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            intent, candidate(listing(26000, SocialStyle.VERY_SOCIAL, true), false, 55, 0.3));

    assertThat(scored.matchScore()).isLessThan(60);
    assertThat(MatchScorer.concernDetails(scored))
        .anySatisfy(d -> assertThat(d).contains("over your"))
        .anySatisfy(d -> assertThat(d).contains("social, lively"));
  }

  @Test
  void weightsRenormalize_whenComponentsMissing() {
    // No budget, no lifestyle, no location in the intent — only always-on components apply
    SearchIntent bare = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).build();
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(bare, candidate(listing(22000, null, null), false, null, null));

    double weightSum = scored.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    // verification .10 + quality .10 + freshness .05
    assertThat(weightSum).isEqualTo(0.25);
    // fully verified + good quality + fresh should still produce a high score after renormalizing
    assertThat(scored.matchScore()).isGreaterThan(80);
  }

  @Test
  void suspiciouslyCheap_isPenalized_notRewarded() {
    SearchIntent intent = intent(30000, null, null);
    MatchScorer.Scored cheap =
        MatchScorer.scoreListing(intent, candidate(listing(6000, null, null), true, null, null));

    MatchScorer.Component budget =
        cheap.breakdown().stream().filter(c -> c.component().equals("budgetFit")).findFirst().orElseThrow();
    assertThat(budget.score()).isEqualTo(0.7);
    assertThat(budget.detail()).contains("unusually low");
  }

  @Test
  void commuteBeyondPreference_reducesLocationScore() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .commuteTo(new SearchIntent.CommuteTo("BKC", UUID.randomUUID(), 30))
            .build();

    MatchScorer.Scored near =
        MatchScorer.scoreListing(intent, candidate(listing(20000, null, null), false, 18, null));
    MatchScorer.Scored far =
        MatchScorer.scoreListing(intent, candidate(listing(20000, null, null), false, 55, null));

    double nearLoc = component(near, "location").score();
    double farLoc = component(far, "location").score();
    assertThat(nearLoc).isGreaterThan(farLoc);
    assertThat(component(far, "location").detail()).contains("~55 min");
  }

  private static MatchScorer.Component component(MatchScorer.Scored scored, String name) {
    return scored.breakdown().stream()
        .filter(c -> c.component().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
