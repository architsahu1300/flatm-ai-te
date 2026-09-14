package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.LocalityResolver;
import com.flatmaite.search.SearchIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockIntentLlmTest {

  private MockLlms.MockIntentLlm llm() {
    LocalityResolver resolver = mock(LocalityResolver.class);
    when(resolver.scan(anyString())).thenReturn(List.of());
    return new MockLlms.MockIntentLlm(new KeywordIntentParser(resolver));
  }

  @Test
  void firstTurn_freeTextIsTheWholeQuery() {
    SearchIntent first = llm().extract("quiet private room under 25k", null);

    assertThat(first.freeText()).isEqualTo("quiet private room under 25k");
    assertThat(first.originalQuery()).isEqualTo("quiet private room under 25k");
    assertThat(first.budgetMax()).isEqualTo(25000);
  }

  @Test
  void refinement_appendsResidualTerms_andKeepsOriginalQuery() {
    MockLlms.MockIntentLlm llm = llm();
    SearchIntent first = llm.extract("quiet private room under 25k", null);

    SearchIntent refined = llm.extract("with a balcony", first);

    assertThat(refined.freeText()).isEqualTo("quiet private room under 25k with a balcony");
    assertThat(refined.originalQuery()).isEqualTo("quiet private room under 25k");
    assertThat(refined.budgetMax()).isEqualTo(25000);
  }

  @Test
  void refinement_withBlankPriorFreeText_usesTheMessage() {
    SearchIntent prior = SearchIntent.builder().budgetMax(20000).originalQuery("room").build();

    SearchIntent refined = llm().extract("with a balcony", prior);

    assertThat(refined.freeText()).isEqualTo("with a balcony");
  }
}
