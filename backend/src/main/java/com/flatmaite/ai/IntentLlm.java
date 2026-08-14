package com.flatmaite.ai;

import com.flatmaite.search.SearchIntent;

/**
 * LLM abstraction for intent extraction. OpenAI impl uses structured outputs; the mock impl
 * delegates to the deterministic keyword parser so everything works key-free.
 */
public interface IntentLlm {

  /**
   * Extracts the FULL updated intent. When {@code prior} is non-null this is a conversational
   * refinement: apply the user's modification on top of the prior intent.
   */
  SearchIntent extract(String query, SearchIntent prior);

  String providerName();

  String model();
}
