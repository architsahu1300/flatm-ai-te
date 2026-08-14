package com.flatmaite.ai;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;

@RequiredArgsConstructor
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

  private final EmbeddingModel embeddingModel;

  @Override
  public float[] embed(String text) {
    return embeddingModel.embed(text);
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    return embeddingModel.embed(texts);
  }

  @Override
  public String providerName() {
    return "openai";
  }
}
