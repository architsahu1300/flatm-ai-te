package com.flatmaite.search;

import com.flatmaite.search.LocalityResolver.Match;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the words around each locality match to decide its role: a place the user wants to live
 * in, a place to avoid, or a place to commute to. Only the first commute-cued mention becomes the
 * anchor; later ones fall back to home.
 */
public record LocationMentions(List<Match> home, List<Match> exclude, Optional<Match> commute) {

  static final Set<String> EXCLUDE_CUES = Set.of("not", "no", "except", "excluding", "avoid", "nahi");
  static final Set<String> EXCLUDE_AFTER = Set.of("nahi", "mat");
  static final Set<String> PREPOSITIONS = Set.of("in", "at", "to", "near", "around", "from", "of");
  static final Set<String> COMMUTE_CUES =
      Set.of("near", "nearby", "close", "around", "next", "within", "work", "working", "office", "commute", "commuting");
  private static final int COMMUTE_WINDOW = 4;

  public static LocationMentions from(List<Tokens.Token> tokens, List<Match> matches) {
    List<Match> home = new ArrayList<>();
    List<Match> exclude = new ArrayList<>();
    Match commute = null;
    for (Match m : matches) {
      if (isExcluded(tokens, m)) {
        exclude.add(m);
      } else if (commute == null && isCommute(tokens, m)) {
        commute = m;
      } else {
        home.add(m);
      }
    }
    return new LocationMentions(home, exclude, Optional.ofNullable(commute));
  }

  private static boolean isExcluded(List<Tokens.Token> tokens, Match m) {
    int i = m.tokenStart();
    String before1 = text(tokens, i - 1);
    String before2 = text(tokens, i - 2);
    String after = text(tokens, m.tokenEnd());
    if (EXCLUDE_AFTER.contains(after)) {
      return true;
    }
    if (EXCLUDE_CUES.contains(before1)) {
      return true;
    }
    if (PREPOSITIONS.contains(before1) && EXCLUDE_CUES.contains(before2)) {
      return true; // "not in andheri"
    }
    String pair = before2 + " " + before1;
    return pair.equals("other than") || pair.equals("anywhere but") || pair.equals("apart from");
  }

  private static boolean isCommute(List<Tokens.Token> tokens, Match m) {
    for (int k = 1; k <= COMMUTE_WINDOW; k++) {
      if (COMMUTE_CUES.contains(text(tokens, m.tokenStart() - k))) {
        return true;
      }
    }
    return false;
  }

  private static String text(List<Tokens.Token> tokens, int index) {
    return index < 0 || index >= tokens.size() ? "" : tokens.get(index).text();
  }
}
