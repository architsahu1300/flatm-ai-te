package com.flatmaite.search;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spelled-out Indian rupee amounts — "twenty five thousand", "one and a half lakh", "1.5 lakh".
 * The glossary promises the LLM these are understood; the keyword parser has to honour the same
 * promise so mock and live modes agree.
 */
public final class NumberWords {

  private NumberWords() {}

  private static final Map<String, Integer> UNITS =
      Map.ofEntries(
          Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
          Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
          Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
          Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
          Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
          Map.entry("nineteen", 19));

  private static final Map<String, Integer> TENS =
      Map.of("twenty", 20, "thirty", 30, "forty", 40, "fifty", 50, "sixty", 60, "seventy", 70,
          "eighty", 80, "ninety", 90);

  private static final Set<String> FILLER = Set.of("and", "a", "an", "rs", "rupees", "inr");

  private static final String CORE_WORDS =
      "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen"
          + "|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
          + "|hundred|thousand|lakhs?|lac";
  private static final String FILLER_OR_DIGIT = "and|a|an|half|\\d+(?:\\.\\d+)?";

  /**
   * A run of number words inside a sentence, e.g. "twenty five thousand" or "1.5 lakh". At least
   * one real number or multiplier word must be present, so filler-only runs ("and a") and bare
   * counts ("a 2 bhk") never read as an amount. Includes trailing whitespace.
   */
  public static final Pattern NUMBER_RUN =
      Pattern.compile(
          "(?:\\b(?:" + FILLER_OR_DIGIT + ")\\b\\s*)*"
              + "\\b(?:" + CORE_WORDS + ")\\b\\s*"
              + "(?:\\b(?:" + CORE_WORDS + "|" + FILLER_OR_DIGIT + ")\\b\\s*)*");

  private static final Pattern NUMERIC = Pattern.compile("\\d+(?:\\.\\d+)?");

  public static OptionalInt parse(String phrase) {
    if (phrase == null || phrase.isBlank()) {
      return OptionalInt.empty();
    }
    double total = 0;
    double current = 0;
    boolean sawNumber = false;
    for (String raw : phrase.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
      String w = raw.replace(",", "");
      if (FILLER.contains(w)) {
        continue;
      }
      if (NUMERIC.matcher(w).matches()) {
        current += Double.parseDouble(w);
        sawNumber = true;
      } else if (UNITS.containsKey(w)) {
        current += UNITS.get(w);
        sawNumber = true;
      } else if (TENS.containsKey(w)) {
        current += TENS.get(w);
        sawNumber = true;
      } else if (w.equals("half")) {
        current += 0.5;
        sawNumber = true;
      } else if (w.equals("hundred")) {
        current = (current == 0 ? 1 : current) * 100;
        sawNumber = true;
      } else if (w.equals("thousand")) {
        total += (current == 0 ? 1 : current) * 1_000;
        current = 0;
        sawNumber = true;
      } else if (w.equals("lakh") || w.equals("lakhs") || w.equals("lac")) {
        total += (current == 0 ? 1 : current) * 100_000;
        current = 0;
        sawNumber = true;
      } else {
        return OptionalInt.empty();
      }
    }
    if (!sawNumber) {
      return OptionalInt.empty();
    }
    return OptionalInt.of((int) Math.round(total + current));
  }
}
