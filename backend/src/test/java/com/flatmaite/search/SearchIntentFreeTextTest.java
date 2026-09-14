package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchIntentFreeTextTest {

  @Test
  void join_isBlankSafeOnBothSides() {
    assertThat(SearchIntent.joinFreeText(null, "balcony")).isEqualTo("balcony");
    assertThat(SearchIntent.joinFreeText("  ", "balcony")).isEqualTo("balcony");
    assertThat(SearchIntent.joinFreeText("sea view", null)).isEqualTo("sea view");
    assertThat(SearchIntent.joinFreeText("sea view", " ")).isEqualTo("sea view");
    assertThat(SearchIntent.joinFreeText(null, null)).isNull();
  }

  @Test
  void join_appendsWithSingleSpace() {
    assertThat(SearchIntent.joinFreeText("sea view ", " balcony")).isEqualTo("sea view balcony");
  }

  @Test
  void join_capsAtMaxChars_keepingTheEarliestTerms() {
    String prior = "wardrobe ".repeat(70).trim(); // 629 chars

    String joined = SearchIntent.joinFreeText(prior, "balcony");

    assertThat(joined.length()).isLessThanOrEqualTo(SearchIntent.MAX_FREE_TEXT_CHARS);
    assertThat(joined).startsWith("wardrobe wardrobe").doesNotContain("balcony");
  }
}
