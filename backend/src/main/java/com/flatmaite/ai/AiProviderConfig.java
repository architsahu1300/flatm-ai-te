package com.flatmaite.ai;

import com.flatmaite.common.config.FlatmaiteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses between mock and real AI providers. Which real provider exists is decided by Spring AI's
 * {@code spring.ai.model.chat} selection (FM_AI_PROVIDER env: openai | google-genai) — the wrappers
 * in {@link OpenAiLlms} are provider-agnostic ChatClient code, so they serve both.
 */
@Configuration
@Slf4j
public class AiProviderConfig {

  public static final String PLACEHOLDER_KEY = "sk-mock-key-not-set";
  public static final String PROVIDER_GEMINI = "google-genai";

  @Bean
  public EmbeddingProvider embeddingProvider(
      FlatmaiteProperties props,
      ObjectProvider<EmbeddingModel> embeddingModel,
      @Value("${spring.ai.model.chat:openai}") String provider,
      @Value("${spring.ai.openai.api-key}") String openaiKey,
      @Value("${spring.ai.google.genai.api-key:}") String geminiKey) {
    if (useMock(props, provider, openaiKey, geminiKey)) {
      log.info("AI provider mode: MOCK (deterministic embeddings, no LLM calls)");
      return new MockEmbeddingProvider();
    }
    EmbeddingModel model = embeddingModel.getObject();
    log.info("AI provider mode: {} ({})", provider.toUpperCase(), model.getClass().getSimpleName());
    return new OpenAiEmbeddingProvider(model, provider);
  }

  @Bean
  public IntentLlm intentLlm(
      FlatmaiteProperties props,
      ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
      com.flatmaite.search.KeywordIntentParser keywordParser,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @Value("${spring.ai.model.chat:openai}") String provider,
      @Value("${spring.ai.openai.api-key}") String openaiKey,
      @Value("${spring.ai.google.genai.api-key:}") String geminiKey,
      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String openaiModel,
      @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash-lite}") String geminiModel) {
    if (useMock(props, provider, openaiKey, geminiKey)) {
      return new MockLlms.MockIntentLlm(keywordParser);
    }
    return new OpenAiLlms.OpenAiIntentLlm(
        org.springframework.ai.chat.client.ChatClient.create(chatModel.getObject()),
        keywordParser,
        objectMapper,
        chatModelName(provider, openaiModel, geminiModel),
        provider);
  }

  @Bean
  public ExplainerLlm explainerLlm(
      FlatmaiteProperties props,
      ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @Value("${spring.ai.model.chat:openai}") String provider,
      @Value("${spring.ai.openai.api-key}") String openaiKey,
      @Value("${spring.ai.google.genai.api-key:}") String geminiKey,
      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String openaiModel,
      @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash-lite}") String geminiModel) {
    if (useMock(props, provider, openaiKey, geminiKey)) {
      return new MockLlms.MockExplainerLlm();
    }
    return new OpenAiLlms.OpenAiExplainerLlm(
        org.springframework.ai.chat.client.ChatClient.create(chatModel.getObject()),
        objectMapper,
        chatModelName(provider, openaiModel, geminiModel),
        provider);
  }

  @Bean
  public com.flatmaite.agreement.ClauseAdvisor clauseAdvisor(
      FlatmaiteProperties props,
      ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @Value("${spring.ai.model.chat:openai}") String provider,
      @Value("${spring.ai.openai.api-key}") String openaiKey,
      @Value("${spring.ai.google.genai.api-key:}") String geminiKey) {
    if (useMock(props, provider, openaiKey, geminiKey)) {
      return new com.flatmaite.agreement.ClauseAdvisor.Mock();
    }
    return new com.flatmaite.agreement.ClauseAdvisor.OpenAi(
        org.springframework.ai.chat.client.ChatClient.create(chatModel.getObject()), objectMapper, provider);
  }

  /** Model name recorded in ai_usage_log — cost table pricing only exists for OpenAI models. */
  private static String chatModelName(String provider, String openaiModel, String geminiModel) {
    return PROVIDER_GEMINI.equals(provider) ? geminiModel : openaiModel;
  }

  public static boolean useMock(
      FlatmaiteProperties props, String provider, String openaiKey, String geminiKey) {
    return switch (props.getAi().getMock()) {
      case "true" -> true;
      case "false" -> false;
      default ->
          PROVIDER_GEMINI.equals(provider)
              ? geminiKey == null || geminiKey.isBlank()
              : openaiKey == null || openaiKey.isBlank() || PLACEHOLDER_KEY.equals(openaiKey);
    };
  }
}
