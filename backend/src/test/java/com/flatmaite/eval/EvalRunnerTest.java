package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.search.SearchIntent;
import org.junit.jupiter.api.Test;

/** Only real provider round-trips are paced and counted; the pipeline's zero-cost heuristics are not. */
class EvalRunnerTest {

  private static final SearchIntent PRIOR = SearchIntent.builder().budgetMax(40000).build();

  @Test
  void firstTurn_needsTheProvider() {
    assertThat(EvalRunner.needsProvider("2bhk in powai under 40k", null)).isTrue();
  }

  @Test
  void heuristicRefinement_doesNotNeedTheProvider() {
    assertThat(EvalRunner.needsProvider("cheaper", PRIOR)).isFalse();
  }

  @Test
  void nonHeuristicRefinement_needsTheProvider() {
    assertThat(EvalRunner.needsProvider("same but in andheri", PRIOR)).isTrue();
  }
}
