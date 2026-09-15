package com.flatmaite.eval;

import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runs a golden set through an extractor. Provider-agnostic: the extractor owns pacing and resolution. */
public final class IntentEvaluator {

  @FunctionalInterface
  public interface Extractor {
    IntentArbiter.Decision extract(String query, SearchIntent prior) throws Exception;
  }

  private IntentEvaluator() {}

  public static EvalReport run(GoldenSet set, Extractor extractor, Function<LocationRef, String> nameOf, Consumer<CaseResult> onCase) {
    List<CaseResult> results = new ArrayList<>();
    for (GoldenCase c : set.cases()) {
      long start = System.currentTimeMillis();
      CaseResult result;
      try {
        IntentArbiter.Decision d = extractor.extract(c.query(), set.priorOf(c));
        List<IntentComparator.SlotResult> slots =
            c.scoreIntent() ? IntentComparator.compare(c.expected(), d.intent(), nameOf) : List.of();
        result = new CaseResult(c, slots, d.verdict(), d.mode(), null, System.currentTimeMillis() - start);
      } catch (Exception e) {
        result = new CaseResult(c, List.of(), null, null, e.getClass().getSimpleName() + ": " + e.getMessage(), System.currentTimeMillis() - start);
      }
      results.add(result);
      onCase.accept(result);
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
    }
    return new EvalReport(results);
  }
}
