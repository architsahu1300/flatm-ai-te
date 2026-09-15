package com.flatmaite.search;

import com.flatmaite.ai.IntentLlm;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one referee for "is this follow-up a new search or a tweak of the current one". The lexical
 * detector rules the clear cases; only for an AMBIGUOUS follow-up does the model's read of the
 * message (its {@code mode}) break the tie. A new request must not inherit the previous search's
 * constraints — a stale locality silently zeroes out results. Shared by the HTTP controller and the
 * intent eval so both judge exactly the same way.
 */
@Component
@RequiredArgsConstructor
public class IntentArbiter {

  public static final String FRESH_NOTE =
      "Started a fresh search — this read as a new request, not a tweak of the last one.";

  /** {@code fresh} = a prior existed and was discarded; only then does the response carry a note. */
  public record Decision(
      SearchIntent intent, NewQueryDetector.Verdict verdict, IntentLlm.Mode mode, boolean fresh) {
    public String note() {
      return fresh ? FRESH_NOTE : null;
    }
  }

  private final NewQueryDetector detector;

  /**
   * @param extract runs the extraction for (query, prior-or-null); called once, or twice when an
   *     ambiguous follow-up is judged NEW by the model. The AMBIGUOUS + NEW path bills two model
   *     calls by design — the fresh extraction cannot reuse a result that was merged onto the prior.
   */
  public Decision decide(
      String query, SearchIntent prior, BiFunction<String, SearchIntent, IntentLlm.Extraction> extract) {
    if (prior == null) {
      IntentLlm.Extraction e = extract.apply(query, null);
      return new Decision(e.intent(), NewQueryDetector.Verdict.NEW, e.mode(), false);
    }
    NewQueryDetector.Verdict verdict = detector.decide(query);
    if (verdict == NewQueryDetector.Verdict.NEW) {
      IntentLlm.Extraction e = extract.apply(query, null);
      return new Decision(e.intent(), verdict, e.mode(), true);
    }
    IntentLlm.Extraction e = extract.apply(query, prior);
    if (verdict == NewQueryDetector.Verdict.AMBIGUOUS && e.mode() == IntentLlm.Mode.NEW) {
      IntentLlm.Extraction fresh = extract.apply(query, null);
      return new Decision(fresh.intent(), verdict, e.mode(), true);
    }
    return new Decision(e.intent(), verdict, e.mode(), false);
  }
}
