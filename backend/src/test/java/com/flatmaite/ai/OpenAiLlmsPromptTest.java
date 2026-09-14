package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiLlmsPromptTest {

  @Test
  void intentSystem_carriesTheVocabularyTheRulesAndTheExamples() {
    String s = OpenAiLlms.intentSystem(List.of("Powai (hiranandani, iit bombay)", "BKC (bandra kurla complex)", "Kurla"));

    assertThat(s).contains("Powai (hiranandani, iit bombay)", "BKC (bandra kurla complex)", "Kurla");
    assertThat(s).contains("excludeLocations", "budgetMin", "single sharing");
    assertThat(s.chars().filter(ch -> ch == '→').count()).isGreaterThanOrEqualTo(9);
    assertThat(s).doesNotContain("REPLACE");
  }

  @Test
  void refineSystem_mergesOnly_andAsksForAMode() {
    assertThat(OpenAiLlms.REFINE_SYSTEM).doesNotContain("REPLACE");
    assertThat(OpenAiLlms.REFINE_SYSTEM).contains("FULL merged intent", "mode", "NEW", "REFINE", "UNSURE");
    // no format placeholders left: the prior intent no longer travels in the system message
    assertThat(OpenAiLlms.REFINE_SYSTEM).doesNotContain("%s");
  }

  @Test
  void refineUserMessage_carriesPriorIntentAndFollowUp() {
    String m = OpenAiLlms.refineUserMessage("{\"budgetMax\":25000}", "with a balcony");

    assertThat(m).contains("Current intent:", "{\"budgetMax\":25000}", "Follow-up:", "with a balcony");
  }
}
