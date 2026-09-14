package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.search.SearchIntent;
import org.junit.jupiter.api.Test;

class OpenAiIntentLlmFinishTest {

  @Test
  void firstTurn_nullFreeText_fallsBackToQuery() {
    SearchIntent extracted = SearchIntent.builder().budgetMax(25000).build();

    SearchIntent out = OpenAiLlms.OpenAiIntentLlm.finish(extracted, "room under 25k", null);

    assertThat(out.freeText()).isEqualTo("room under 25k");
    assertThat(out.originalQuery()).isEqualTo("room under 25k");
  }

  @Test
  void refinement_nullFreeText_keepsPriorResidual_notTheTweak() {
    SearchIntent prior =
        SearchIntent.builder()
            .freeText("balcony sea view")
            .originalQuery("room with balcony sea view")
            .build();
    SearchIntent extracted = SearchIntent.builder().budgetMax(20000).build();

    SearchIntent out = OpenAiLlms.OpenAiIntentLlm.finish(extracted, "cheaper", prior);

    assertThat(out.freeText()).isEqualTo("balcony sea view");
    assertThat(out.originalQuery()).isEqualTo("room with balcony sea view");
  }

  @Test
  void llmProvidedFreeText_alwaysWins() {
    SearchIntent extracted = SearchIntent.builder().freeText("sea view").build();

    SearchIntent out = OpenAiLlms.OpenAiIntentLlm.finish(extracted, "sea view flat in worli", null);

    assertThat(out.freeText()).isEqualTo("sea view");
  }
}
