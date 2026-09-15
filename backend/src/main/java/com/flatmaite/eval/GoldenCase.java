package com.flatmaite.eval;

import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.util.List;

/** One golden case: a query (optionally a follow-up to an earlier case) and the intent it should yield. */
public record GoldenCase(
    String id,
    List<String> tags,
    String query,
    String priorCase,
    boolean mustPass,
    NewQueryDetector.Verdict expectVerdict,
    boolean scoreIntent,
    SearchIntent expected) {

  public static final String KNOWN_GAP = "known-gap";

  public boolean knownGap() {
    return tags.contains(KNOWN_GAP);
  }
}
