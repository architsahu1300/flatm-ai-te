package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.LocalityResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentLlmModeTest {

  @Test
  void parse_isLenient() {
    assertThat(IntentLlm.Mode.parse("NEW")).isEqualTo(IntentLlm.Mode.NEW);
    assertThat(IntentLlm.Mode.parse(" refine ")).isEqualTo(IntentLlm.Mode.REFINE);
    assertThat(IntentLlm.Mode.parse("UNSURE")).isEqualTo(IntentLlm.Mode.UNSURE);
    assertThat(IntentLlm.Mode.parse("banana")).isEqualTo(IntentLlm.Mode.UNSURE);
    assertThat(IntentLlm.Mode.parse(null)).isEqualTo(IntentLlm.Mode.UNSURE);
  }

  @Test
  void mockLlm_neverOffersAnOpinion() {
    LocalityResolver resolver = mock(LocalityResolver.class);
    when(resolver.scan(anyString())).thenReturn(List.of());
    MockLlms.MockIntentLlm llm = new MockLlms.MockIntentLlm(new KeywordIntentParser(resolver));

    IntentLlm.Extraction first = llm.extractWithMode("room under 25k", null);
    IntentLlm.Extraction refined = llm.extractWithMode("with a balcony", first.intent());

    assertThat(first.mode()).isEqualTo(IntentLlm.Mode.NONE);
    assertThat(refined.mode()).isEqualTo(IntentLlm.Mode.NONE);
    assertThat(refined.intent().budgetMax()).isEqualTo(25000);
  }

  @Test
  void refineResult_parsesWithAndWithoutMode() throws Exception {
    ObjectMapper om = new ObjectMapper();

    RefineResult withMode =
        om.readValue("{\"intent\":{\"budgetMax\":20000},\"mode\":\"REFINE\",\"extra\":1}", RefineResult.class);
    RefineResult withoutMode = om.readValue("{\"intent\":{\"budgetMax\":20000}}", RefineResult.class);

    assertThat(withMode.intent().budgetMax()).isEqualTo(20000);
    assertThat(IntentLlm.Mode.parse(withMode.mode())).isEqualTo(IntentLlm.Mode.REFINE);
    assertThat(IntentLlm.Mode.parse(withoutMode.mode())).isEqualTo(IntentLlm.Mode.UNSURE);
  }
}
