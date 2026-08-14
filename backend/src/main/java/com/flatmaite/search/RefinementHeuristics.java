package com.flatmaite.search;

import java.util.Locale;

/**
 * Zero-cost regex pre-pass for the ~10 highest-frequency refinements. Anything unmatched goes to
 * the LLM (or its mock). Returns null when no pattern applies.
 */
public final class RefinementHeuristics {

  private RefinementHeuristics() {}

  public static SearchIntent apply(SearchIntent prior, String query) {
    if (prior == null) {
      return null;
    }
    String q = query.toLowerCase(Locale.ROOT).trim();

    if (q.matches(".*\\b(cheaper|less expensive|lower budget|too expensive|reduce budget)\\b.*")) {
      Integer max = prior.budgetMax();
      if (max == null) {
        return null;
      }
      return prior.toBuilder().budgetMax((int) Math.round(max * 0.9 / 500) * 500).freeText(query).build();
    }
    if (q.matches(".*\\bonly\\s+(show\\s+)?verified\\b.*") || q.matches(".*\\bverified (listings|only)\\b.*")) {
      return prior.toBuilder().verifiedOnly(true).freeText(query).build();
    }
    if (q.matches(".*\\b(closer|nearer)( to work)?\\b.*") && prior.commuteTo() != null) {
      int current = prior.commuteTo().maxMinutes() == null ? 45 : prior.commuteTo().maxMinutes();
      return prior.toBuilder()
          .commuteTo(
              new SearchIntent.CommuteTo(
                  prior.commuteTo().place(), prior.commuteTo().localityId(), Math.max(10, (int) (current * 0.8))))
          .freeText(query)
          .build();
    }
    if (q.matches(".*\\b(show|find)( me)? flatmates?( instead)?\\b.*")) {
      return prior.toBuilder().searchTarget(com.flatmaite.common.domain.SearchTarget.FLATMATES).freeText(query).build();
    }
    if (q.matches(".*\\b(show|find)( me)? (homes|rooms|flats|places)( instead)?\\b.*") && q.length() < 40) {
      return prior.toBuilder().searchTarget(com.flatmaite.common.domain.SearchTarget.PROPERTIES).freeText(query).build();
    }
    if (q.matches(".*\\bonly (fully )?furnished\\b.*") || q.equals("furnished only")) {
      return prior.toBuilder().furnished(com.flatmaite.common.domain.Furnishing.FULLY_FURNISHED).freeText(query).build();
    }
    if (q.matches(".*\\bbigger budget|increase (the )?budget|can go up to\\b.*")) {
      Integer max = prior.budgetMax();
      if (max == null) {
        return null;
      }
      return prior.toBuilder().budgetMax((int) Math.round(max * 1.15 / 500) * 500).freeText(query).build();
    }
    return null;
  }

  /** Deictic questions that should not re-run extraction at all. */
  public static boolean isWhyQuestion(String query) {
    String q = query.toLowerCase(Locale.ROOT);
    return q.startsWith("why") || q.contains("why is this") || q.contains("why did you");
  }
}
