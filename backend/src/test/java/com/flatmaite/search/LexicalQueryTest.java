package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LexicalQueryTest {

  @Test
  void joinsSurvivingTokensWithOr() {
    // "in"/"no" too short, "25k" is a budget token; "under" survives (Postgres drops it as a stop word)
    assertThat(HybridRetriever.lexicalQuery("quiet 1bhk in andheri under 25k no smokers"))
        .isEqualTo("quiet or 1bhk or andheri or under or smokers");
  }

  @Test
  void dropsShortNumericAndBudgetTokens() {
    assertThat(HybridRetriever.lexicalQuery("2 bhk 30000 25k pg")).isEqualTo("bhk");
  }

  @Test
  void dedupesPreservingFirstOrder() {
    assertThat(HybridRetriever.lexicalQuery("balcony room balcony")).isEqualTo("balcony or room");
  }

  @Test
  void nullWhenNothingSurvives() {
    assertThat(HybridRetriever.lexicalQuery(null)).isNull();
    assertThat(HybridRetriever.lexicalQuery("")).isNull();
    assertThat(HybridRetriever.lexicalQuery("a 2 25k")).isNull();
  }

  @Test
  void toleratesPunctuationAndCase() {
    assertThat(HybridRetriever.lexicalQuery("Sea-facing!! \"Terrace\", (near) metro:"))
        .isEqualTo("sea or facing or terrace or near or metro");
  }

  @Test
  void capsAtTwentyFourDistinctTokens() {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 30; i++) {
      sb.append("term").append(i).append(' ');
    }

    String query = HybridRetriever.lexicalQuery(sb.toString());

    assertThat(query.split(" or ")).hasSize(24);
    assertThat(query).startsWith("term1 or term2").endsWith("term24");
  }
}
