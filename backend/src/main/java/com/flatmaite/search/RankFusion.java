package com.flatmaite.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion over ordered id lists. Only positions count, never scores, so a cosine
 * ranking (0–1) and a ts_rank ranking (unbounded) merge without normalizing either. Appearing in
 * both lists beats a high placement in one; k = 60 flattens the top of the curve so agreement
 * between rankings matters more than being #1 in a single one.
 */
public final class RankFusion {

  private RankFusion() {}

  public static final int K = 60;

  /** One fused entry: the raw RRF score and the same score divided by the top score (top = 1.0). */
  public record Fused(UUID id, double rrf, double normalized) {}

  /**
   * @param rankings ordered id lists, best first. Absence from a list contributes nothing.
   * @return descending by RRF; ties keep first-appearance order across the rankings as given.
   */
  public static List<Fused> fuse(List<List<UUID>> rankings) {
    // insertion order = first appearance, which the stable sort below preserves for ties
    Map<UUID, Double> scores = new LinkedHashMap<>();
    for (List<UUID> ranking : rankings) {
      for (int i = 0; i < ranking.size(); i++) {
        scores.merge(ranking.get(i), 1.0 / (K + i + 1), Double::sum);
      }
    }
    if (scores.isEmpty()) {
      return List.of();
    }
    List<Map.Entry<UUID, Double>> ordered = new ArrayList<>(scores.entrySet());
    ordered.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
    double top = ordered.get(0).getValue();
    List<Fused> out = new ArrayList<>(ordered.size());
    for (Map.Entry<UUID, Double> e : ordered) {
      out.add(new Fused(e.getKey(), e.getValue(), e.getValue() / top));
    }
    return out;
  }
}
