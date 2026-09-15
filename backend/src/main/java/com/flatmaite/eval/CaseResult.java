package com.flatmaite.eval;

import com.flatmaite.eval.IntentComparator.SlotResult;
import com.flatmaite.search.NewQueryDetector;
import java.util.List;

/** One golden case's outcome. {@code error} is set when the extractor threw; then no slots exist. */
public record CaseResult(GoldenCase golden, List<SlotResult> slots, NewQueryDetector.Verdict verdict, String error, long millis) {

  public boolean verdictMatches() {
    return golden.expectVerdict() == null || golden.expectVerdict() == verdict;
  }

  public boolean passed() {
    return error == null && slots.stream().allMatch(SlotResult::match) && verdictMatches();
  }

  public boolean knownGap() {
    return golden.knownGap();
  }

  /** The one line a reader needs: the first failing slot, the verdict miss, or the error. */
  public String firstMismatch() {
    if (error != null) {
      return "error: " + error;
    }
    for (SlotResult s : slots) {
      if (!s.match()) {
        return s.slot() + ": expected " + s.expected() + ", got " + s.actual();
      }
    }
    if (!verdictMatches()) {
      return "verdict: expected " + golden.expectVerdict() + ", got " + verdict;
    }
    return "";
  }
}
