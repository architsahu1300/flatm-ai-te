package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.AiUsageService;
import com.flatmaite.search.LocalityResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OpenAiLlmsPromptTest {

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  private static OpenAiLlms.OpenAiIntentLlm intentLlm(LocalityResolver resolver) {
    return new OpenAiLlms.OpenAiIntentLlm(null, null, new ObjectMapper(), "gpt-4o-mini", "openai", resolver);
  }

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

  @Test
  void promptOverheadTokens_reflectsTheActualSystemPrompt_notAHardcodedGuess() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(List.of(locality("Powai", "hiranandani")));
    LocalityResolver resolver = new LocalityResolver(repo);
    resolver.reload();

    int overhead = intentLlm(resolver).promptOverheadTokens();

    assertThat(overhead).isGreaterThan(700);
    assertThat(overhead)
        .isGreaterThanOrEqualTo(AiUsageService.estimateTokens(OpenAiLlms.intentSystem(resolver.vocabulary())));
  }

  @Test
  void currentIntentSystem_rebuildsAfterTheResolverReloads() {
    // "Chembur" (unlike Powai/Andheri/BKC/Malad/Goregaon/Dadar) never appears in the static
    // EXAMPLES block, so its presence in the prompt can only come from the live vocabulary.
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(List.of(locality("Powai", "hiranandani")))
        .thenReturn(List.of(locality("Powai", "hiranandani"), locality("Chembur")));
    LocalityResolver resolver = new LocalityResolver(repo);
    resolver.reload();
    OpenAiLlms.OpenAiIntentLlm llm = intentLlm(resolver);

    assertThat(llm.currentIntentSystem()).doesNotContain("Chembur");

    resolver.reload();

    assertThat(llm.currentIntentSystem()).contains("Chembur");
  }

  @Test
  void theIntentPromptAsksTheModelToRateWhatTheUserActuallySaid() {
    String system = OpenAiLlms.intentSystem(List.of("Powai", "BKC"));
    assertThat(system).contains("confidence");
    assertThat(system).contains("1.0 when the user states it outright");
    assertThat(system).contains("0.5 when you inferred it");
  }
}
