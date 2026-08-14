package com.flatmaite.ai;

import com.flatmaite.search.SearchIntent;
import java.util.List;
import java.util.UUID;

/** LLM abstraction for batched match explanations, grounded in the deterministic breakdown. */
public interface ExplainerLlm {

  record CandidateFacts(UUID id, String title, List<String> positives, List<String> concerns, int matchScore) {}

  record Explanation(UUID id, List<String> matchReasons, List<String> concerns) {}

  List<Explanation> explainBatch(SearchIntent intent, List<CandidateFacts> candidates);

  String providerName();

  String model();
}
