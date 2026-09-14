package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.listing.Listing;
import com.flatmaite.search.HybridRetriever.Retrieval;
import com.flatmaite.search.MatchScorer.ListingCandidate;
import com.flatmaite.search.SearchIntent.Lifestyle;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchScorerTest {

  private static final Retrieval SEMANTIC_TOP = new Retrieval(1.0, true, false);

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

  private ListingCandidate candidate(Listing l, boolean preferred, Integer commute, Retrieval retrieval) {
    return new ListingCandidate(
        l, UUID.randomUUID(), "BKC", true, true, true, retrieval, commute, preferred);
  }

  @Test
  void perfectMatch_scoresHigh_withPositiveDetails() {
    SearchIntent intent = intent(25000, true, "NO_SMOKERS");
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            intent, candidate(listing(22000, SocialStyle.QUIET, false), true, null, new Retrieval(0.9, true, false)));

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
            intent,
            candidate(listing(26000, SocialStyle.VERY_SOCIAL, true), false, 55, new Retrieval(0.3, true, false)));

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
        MatchScorer.scoreListing(bare, candidate(listing(22000, null, null), false, null, SEMANTIC_TOP));

    double weightSum = scored.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    // relevance .15 + verification .10 + quality .10 + freshness .05 — relevance is always on
    assertThat(weightSum).isCloseTo(0.40, within(1e-9));
    // fully verified + good quality + fresh + top relevance should still score high after renormalizing
    assertThat(scored.matchScore()).isGreaterThan(80);
  }

  @Test
  void relevance_alwaysApplies_evenWithNoHits() {
    SearchIntent bare = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).build();
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            bare, candidate(listing(22000, null, null), false, null, new Retrieval(0.5, false, false)));

    MatchScorer.Component relevance = component(scored, "relevance");
    assertThat(relevance.weight()).isEqualTo(0.15);
    assertThat(relevance.score()).isEqualTo(0.5);
    assertThat(relevance.detail()).isNull();
  }

  @Test
  void lexicalOnlyHit_doesNotOutscore_strongerSemanticHit() {
    // The old bug: a candidate with no similarity had the component skipped and its weight
    // redistributed, so it could beat a candidate with real-but-weaker evidence.
    SearchIntent intent = intent(25000, true, "NO_SMOKERS");
    Listing same = listing(22000, SocialStyle.QUIET, false);
    MatchScorer.Scored semantic =
        MatchScorer.scoreListing(intent, candidate(same, true, null, new Retrieval(0.8, true, false)));
    MatchScorer.Scored lexicalOnly =
        MatchScorer.scoreListing(intent, candidate(same, true, null, new Retrieval(0.5, false, true)));

    assertThat(semantic.matchScore()).isGreaterThan(lexicalOnly.matchScore());
    // both carry the component — nothing was skipped or renormalized differently
    double semanticWeights = semantic.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    double lexicalWeights = lexicalOnly.breakdown().stream().mapToDouble(MatchScorer.Component::weight).sum();
    assertThat(semanticWeights).isEqualTo(lexicalWeights);
    assertThat(component(lexicalOnly, "relevance").detail())
        .isEqualTo("Mentions the specific things you asked for");
  }

  @Test
  void relevanceDetail_reflectsBothSources() {
    SearchIntent bare = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES).build();
    MatchScorer.Scored scored =
        MatchScorer.scoreListing(
            bare, candidate(listing(22000, null, null), false, null, new Retrieval(1.0, true, true)));

    assertThat(component(scored, "relevance").detail())
        .isEqualTo("Matches your description on both wording and meaning");
    assertThat(MatchScorer.positiveDetails(scored))
        .contains("Matches your description on both wording and meaning");
  }

  @Test
  void suspiciouslyCheap_isPenalized_notRewarded() {
    SearchIntent intent = intent(30000, null, null);
    MatchScorer.Scored cheap =
        MatchScorer.scoreListing(intent, candidate(listing(6000, null, null), true, null, SEMANTIC_TOP));

    MatchScorer.Component budget = component(cheap, "budgetFit");
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
        MatchScorer.scoreListing(intent, candidate(listing(20000, null, null), false, 18, SEMANTIC_TOP));
    MatchScorer.Scored far =
        MatchScorer.scoreListing(intent, candidate(listing(20000, null, null), false, 55, SEMANTIC_TOP));

    double nearLoc = component(near, "location").score();
    double farLoc = component(far, "location").score();
    assertThat(nearLoc).isGreaterThan(farLoc);
    assertThat(component(far, "location").detail()).contains("~55 min");
  }

  @Test
  void flatmateRelevanceDetail_matchesSpec() {
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, true, true)))
        .isEqualTo("Matches your description on both wording and meaning");
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, true, false)))
        .isEqualTo("Their profile matches your description");
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, false, true)))
        .isEqualTo("Their profile mentions what you asked for");
    assertThat(MatchScorer.flatmateRelevanceDetail(new Retrieval(1, false, false)))
        .isNull();
  }

  @Test
  void listingRelevanceDetail_matchesSpec() {
    assertThat(MatchScorer.listingRelevanceDetail(new Retrieval(1, true, true)))
        .isEqualTo("Matches your description on both wording and meaning");
    assertThat(MatchScorer.listingRelevanceDetail(new Retrieval(1, true, false)))
        .isEqualTo("Description matches what you asked for");
    assertThat(MatchScorer.listingRelevanceDetail(new Retrieval(1, false, true)))
        .isEqualTo("Mentions the specific things you asked for");
    assertThat(MatchScorer.listingRelevanceDetail(new Retrieval(1, false, false))).isNull();
  }

  private static MatchScorer.Component component(MatchScorer.Scored scored, String name) {
    return scored.breakdown().stream()
        .filter(c -> c.component().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
