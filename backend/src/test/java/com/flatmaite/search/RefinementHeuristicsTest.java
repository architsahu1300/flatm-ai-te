package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.SearchTarget;
import org.junit.jupiter.api.Test;

class RefinementHeuristicsTest {

  private final SearchIntent prior =
      SearchIntent.builder()
          .searchTarget(SearchTarget.PROPERTIES)
          .budgetMax(25000)
          .commuteTo(new SearchIntent.CommuteTo("BKC", null, 45))
          .freeText("quiet room near BKC with a balcony")
          .originalQuery("quiet room near BKC with a balcony under 25k")
          .build();

  @Test
  void cheaper_reducesBudget_andKeepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "show me cheaper");

    assertThat(out.budgetMax()).isLessThan(25000);
    assertThat(out.freeText()).isEqualTo(prior.freeText());
    assertThat(out.originalQuery()).isEqualTo(prior.originalQuery());
  }

  @Test
  void onlyVerified_keepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "only verified");

    assertThat(out.verifiedOnly()).isTrue();
    assertThat(out.freeText()).isEqualTo(prior.freeText());
  }

  @Test
  void furnishedOnly_keepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "furnished only");

    assertThat(out.furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
    assertThat(out.freeText()).isEqualTo(prior.freeText());
  }

  @Test
  void closer_tightensCommute_andKeepsResidualFreeText() {
    SearchIntent out = RefinementHeuristics.apply(prior, "closer to work");

    assertThat(out.commuteTo().maxMinutes()).isEqualTo(36);
    assertThat(out.freeText()).isEqualTo(prior.freeText());
  }

  @Test
  void unmatchedMessage_returnsNull() {
    assertThat(RefinementHeuristics.apply(prior, "with a sea view")).isNull();
  }
}
