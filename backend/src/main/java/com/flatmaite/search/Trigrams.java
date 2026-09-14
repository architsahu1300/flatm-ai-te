package com.flatmaite.search;

import java.util.HashSet;
import java.util.Set;

/** pg_trgm-style similarity, in memory: Jaccard over trigrams of the string padded "  s ". */
final class Trigrams {

  private Trigrams() {}

  static double similarity(String a, String b) {
    Set<String> ta = trigrams(a);
    Set<String> tb = trigrams(b);
    if (ta.isEmpty() || tb.isEmpty()) {
      return 0.0;
    }
    int shared = 0;
    for (String t : ta) {
      if (tb.contains(t)) {
        shared++;
      }
    }
    int union = ta.size() + tb.size() - shared;
    return union == 0 ? 0.0 : (double) shared / union;
  }

  static Set<String> trigrams(String s) {
    Set<String> out = new HashSet<>();
    if (s == null || s.isEmpty()) {
      return out;
    }
    String padded = "  " + s + " ";
    for (int i = 0; i + 3 <= padded.length(); i++) {
      out.add(padded.substring(i, i + 3));
    }
    return out;
  }
}
