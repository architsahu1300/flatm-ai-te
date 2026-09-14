package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.search.SearchIntent.Lifestyle;
import org.junit.jupiter.api.Test;

class SemanticTextTest {

  @Test
  void originalQueryLeads_andDistinctResidualFollows() {
    SearchIntent intent =
        SearchIntent.builder().originalQuery("quiet room near BKC").freeText("sea facing").build();

    assertThat(HybridRetriever.semanticText(intent)).isEqualTo("quiet room near BKC. sea facing");
  }

  @Test
  void accumulatedResidual_appendsOnlyTheRemainder() {
    SearchIntent intent =
        SearchIntent.builder()
            .originalQuery("quiet room near BKC")
            .freeText("quiet room near BKC with a balcony")
            .build();

    assertThat(HybridRetriever.semanticText(intent)).isEqualTo("quiet room near BKC. with a balcony");
  }

  @Test
  void identicalResidual_isNotDuplicated() {
    SearchIntent intent =
        SearchIntent.builder().originalQuery("quiet room near BKC").freeText("quiet room near BKC").build();

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
