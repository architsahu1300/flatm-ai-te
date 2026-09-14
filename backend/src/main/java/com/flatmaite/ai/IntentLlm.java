package com.flatmaite.ai;

import com.flatmaite.search.SearchIntent;
import java.util.Locale;

/**
 * LLM abstraction for intent extraction. The provider impl uses structured outputs; the mock impl
 * delegates to the deterministic keyword parser so everything works key-free.
 */
public interface IntentLlm {

  /** The model's opinion on a follow-up. NONE = no opinion offered (mock, heuristics, first turn). */
  enum Mode {
    NEW,
    REFINE,
    UNSURE,
    NONE;

    /** Lenient read of the model's report; anything unexpected is UNSURE. */
    public static Mode parse(String raw) {
      if (raw == null) {
        return UNSURE;
      }
      return switch (raw.trim().toUpperCase(Locale.ROOT)) {
        case "NEW" -> NEW;
        case "REFINE" -> REFINE;
        default -> UNSURE;
      };
    }
  }

  record Extraction(SearchIntent intent, Mode mode) {}

  /**
   * Extracts the FULL updated intent. When {@code prior} is non-null this is a conversational
   * refinement: apply the user's modification on top of the prior intent.
   */
  SearchIntent extract(String query, SearchIntent prior);

  /** The intent plus the model's opinion on whether the follow-up was a new search. */
  default Extraction extractWithMode(String query, SearchIntent prior) {
    return new Extraction(extract(query, prior), Mode.NONE);
  }

  /**
   * Minimal round-trip that must propagate provider errors — {@link #extract} deliberately swallows
   * them to fall back, which hides a dead key or retired model. Mock impls stay no-ops.
   */
  default void healthCheck() {}

  /**
   * Estimated prompt-token overhead of a call beyond the user's query — charged against the daily
   * cost budget. The mock/heuristic paths cost nothing real, so 700 is a conservative placeholder;
   * a provider that assembles a real system prompt should override this with the actual size so the
   * budget guard is not silently under-counting.
   */
  default int promptOverheadTokens() {
    return 700;
  }

  String providerName();

  String model();
}
