package com.flatmaite.ai;

import java.util.List;

/**
 * Provider abstraction for text embeddings. Implementations: OpenAI (text-embedding-3-small,
 * 1536d) and a deterministic mock so the entire stack runs key-free.
 */
public interface EmbeddingProvider {

  int DIMENSIONS = 1536;

  float[] embed(String text);

  List<float[]> embedBatch(List<String> texts);

  String providerName();
}
