package com.flatmaite.search;

import com.flatmaite.ai.ExplainerLlm;
import com.flatmaite.ai.ExplainerLlm.CandidateFacts;
import com.flatmaite.ai.ExplainerLlm.Explanation;
import com.flatmaite.ai.MockLlms;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Match explanations: ONE batched LLM call for the top-N, deterministic breakdown templates for the
 * tail, all cached per (intentHash, candidateId, updatedAt) so listing edits invalidate naturally.
 * Kill-switch AI_EXPLANATIONS_ENABLED=false drops to templates entirely.
 */
@Service
@RequiredArgsConstructor
public class ExplanationService {

  public static final int LLM_TOP_N = 8;

  private final ExplainerLlm explainerLlm;
  private final FlatmaiteProperties props;
  private final MockLlms.MockExplainerLlm templates = new MockLlms.MockExplainerLlm();

  private final Cache<String, Explanation> cache =
      Caffeine.newBuilder().maximumSize(20_000).expireAfterWrite(Duration.ofHours(24)).build();

  public record Explainable(UUID id, String title, MatchScorer.Scored scored, Instant updatedAt) {}

  /** Returns an explanation per candidate; llmBudget candidates at the head may use the LLM. */
  public Map<UUID, Explanation> explain(SearchIntent intent, String intentHash, List<Explainable> ranked) {
    Map<UUID, Explanation> out = new HashMap<>();
    boolean llmEnabled = props.getAi().isExplanationsEnabled();

    List<Explainable> needLlm = new ArrayList<>();
    for (int i = 0; i < ranked.size(); i++) {
      Explainable e = ranked.get(i);
      String key = cacheKey(intentHash, e);
      Explanation cached = cache.getIfPresent(key);
      if (cached != null) {
        out.put(e.id(), cached);
      } else if (llmEnabled && i < LLM_TOP_N) {
        needLlm.add(e);
      } else {
        Explanation templated = template(intent, e);
        cache.put(key, templated);
        out.put(e.id(), templated);
      }
    }

    if (!needLlm.isEmpty()) {
      List<CandidateFacts> facts = needLlm.stream().map(ExplanationService::factsOf).toList();
      List<Explanation> explained = explainerLlm.explainBatch(intent, facts);
      Map<UUID, Explanation> byId = new HashMap<>();
      explained.forEach(e -> byId.put(e.id(), e));
      for (Explainable e : needLlm) {
        Explanation explanation = byId.getOrDefault(e.id(), template(intent, e));
        cache.put(cacheKey(intentHash, e), explanation);
        out.put(e.id(), explanation);
      }
    }
    return out;
  }

  public boolean usesLlm() {
    return props.getAi().isExplanationsEnabled() && !"mock".equals(explainerLlm.providerName());
  }

  private Explanation template(SearchIntent intent, Explainable e) {
    return templates.explainBatch(intent, List.of(factsOf(e))).get(0);
  }

  private static CandidateFacts factsOf(Explainable e) {
    return new CandidateFacts(
        e.id(),
        e.title(),
        MatchScorer.positiveDetails(e.scored()),
        MatchScorer.concernDetails(e.scored()),
        e.scored().matchScore());
  }

  private static String cacheKey(String intentHash, Explainable e) {
    return intentHash + ":" + e.id() + ":" + (e.updatedAt() == null ? 0 : e.updatedAt().toEpochMilli());
  }
}
