package com.flatmaite.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one tokenisation shared by the locality resolver, the keyword parser and the new-query
 * detector: lower-cased runs of letters/digits with their character spans. Sharing it is what lets
 * a resolver match be located relative to the words around it.
 */
public final class Tokens {

  private Tokens() {}

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

  /** One word; {@code start}/{@code end} index the lower-cased text. */
  public record Token(String text, int start, int end) {}

  public static List<Token> of(String text) {
    List<Token> out = new ArrayList<>();
    if (text == null) {
      return out;
    }
    Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (m.find()) {
      out.add(new Token(m.group(), m.start(), m.end()));
    }
    return out;
  }

  /** Joins {@code tokens[from, to)} with single spaces. */
  public static String phrase(List<Token> tokens, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      if (i > from) {
        sb.append(' ');
      }
      sb.append(tokens.get(i).text());
    }
    return sb.toString();
  }
}
