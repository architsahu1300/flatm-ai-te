package com.flatmaite.ai;

import com.flatmaite.common.config.FlatmaiteProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AiProviderConfig {

  public static final String PLACEHOLDER_KEY = "sk-mock-key-not-set";

  @Bean
  public EmbeddingProvider embeddingProvider(
      FlatmaiteProperties props,
      ObjectProvider<EmbeddingModel> embeddingModel,
      @Value("${spring.ai.openai.api-key}") String apiKey) {
    if (useMock(props, apiKey)) {
      log.info("AI provider mode: MOCK (deterministic embeddings, no OpenAI calls)");
      return new MockEmbeddingProvider();
    }
    EmbeddingModel model = embeddingModel.getObject();
    log.info("AI provider mode: OPENAI ({})", model.getClass().getSimpleName());
    return new OpenAiEmbeddingProvider(model);
  }

  @Bean
  public IntentLlm intentLlm(
      FlatmaiteProperties props,
      ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
      com.flatmaite.search.KeywordIntentParser keywordParser,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @Value("${spring.ai.openai.api-key}") String apiKey,
      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model) {
    if (useMock(props, apiKey)) {
      return new MockLlms.MockIntentLlm(keywordParser);
    }
    return new OpenAiLlms.OpenAiIntentLlm(
        org.springframework.ai.chat.client.ChatClient.create(chatModel.getObject()),
        keywordParser,
        objectMapper,
        model);
  }

  @Bean
  public ExplainerLlm explainerLlm(
      FlatmaiteProperties props,
      ObjectProvider<org.springframework.ai.chat.model.ChatModel> chatModel,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @Value("${spring.ai.openai.api-key}") String apiKey,
      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model) {
    if (useMock(props, apiKey)) {
      return new MockLlms.MockExplainerLlm();
    }
    return new OpenAiLlms.OpenAiExplainerLlm(
        org.springframework.ai.chat.client.ChatClient.create(chatModel.getObject()), objectMapper, model);
  }

  public static boolean useMock(FlatmaiteProperties props, String apiKey) {
    return switch (props.getAi().getMock()) {
      case "true" -> true;
      case "false" -> false;
      default -> apiKey == null || apiKey.isBlank() || PLACEHOLDER_KEY.equals(apiKey);
    };
  }
}
