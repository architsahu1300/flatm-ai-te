package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.search.SearchIntent.Lifestyle;
import org.junit.jupiter.api.Test;

class SemanticTextTest {

  @Test
  void originalQueryWins_evenWhenFreeTextDiffers() {
    // the old code embedded freeText, which after a refinement was the single word "cheaper"
    SearchIntent intent =
        SearchIntent.builder().originalQuery("quiet room near BKC").freeText("cheaper").build();

    assertThat(HybridRetriever.semanticText(intent)).isEqualTo("quiet room near BKC");
  }

  @Test
  void lifestyleTags_areAppendedToTheOriginalQuery() {
    SearchIntent intent =
        SearchIntent.builder()
            .originalQuery("quiet room near BKC")
            .lifestyle(Lifestyle.builder().quiet(true).smoking("NO_SMOKERS").build())
            .build();

    assertThat(HybridRetriever.semanticText(intent))
        .startsWith("quiet room near BKC")
        .contains("quiet calm peaceful home")
        .contains("non-smoking household");
  }

  @Test
  void fallsBackToFreeText_whenThereIsNoOriginalQuery() {
    SearchIntent intent = SearchIntent.builder().freeText("balcony").build();

    assertThat(HybridRetriever.semanticText(intent)).isEqualTo("balcony");
  }
}
