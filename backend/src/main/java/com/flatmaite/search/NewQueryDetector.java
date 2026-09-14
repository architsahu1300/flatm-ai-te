package com.flatmaite.search;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one referee for "is this follow-up a new search or a tweak of the current one". Getting it
 * wrong one way strands users on constraints they never asked for; the other way wipes out what
 * they built up. Lexical rules decide the clear cases; the controller consults the model's opinion
 * only for {@link Verdict#AMBIGUOUS}.
 */
@Component
@RequiredArgsConstructor
public class NewQueryDetector {

  public enum Verdict {
    NEW,
    REFINE,
    AMBIGUOUS
  }

  private static final Pattern FRESH_CUE =
      Pattern.compile("\\b(forget that|forget it|start over|new search|scrap that|from scratch)\\b");
  private static final Pattern REFINEMENT_CUE =
      Pattern.compile(
          "\\b(make it|instead|also|actually|same but|but in|rather|change it|change the|cheaper|closer|nearer)\\b");
  private static final Pattern BUDGET =
      Pattern.compile("(\\d+(?:\\.\\d+)?\\s*k\\b)|(\\d{4,7})|(\\d+(?:\\.\\d+)?\\s*(?:lakh|lac)\\b)");
  private static final Pattern BHK = Pattern.compile("\\d\\s*bhk");
  private static final Pattern HOUSING_NOUN =
      Pattern.compile("\\b(flatmate|roommate|pg|paying guest|apartment|flat|flats|room|rooms|studio|1rk)\\b");

  private final LocalityResolver localityResolver;

  public Verdict decide(String query) {
    if (query == null || query.isBlank()) {
      return Verdict.REFINE;
    }
    String q = query.toLowerCase(Locale.ROOT);
    if (FRESH_CUE.matcher(q).find()) {
      return Verdict.NEW;
    }
    int anchors = anchors(q);
    if (anchors >= 3) {
      return Verdict.NEW;
    }
    if (REFINEMENT_CUE.matcher(q).find()) {
      return Verdict.REFINE;
    }
    if (anchors >= 2 && HOUSING_NOUN.matcher(q).find()) {
      return Verdict.NEW;
    }
    if (anchors == 0) {
      return Verdict.REFINE;
    }
    return Verdict.AMBIGUOUS;
  }

  /** A request the detector alone rules NEW. */
  public boolean isSelfContained(String query) {
    return decide(query) == Verdict.NEW;
  }

  /** Independent anchors: a confidently recognised locality, a budget, an occupancy word, a BHK. */
  private int anchors(String q) {
    int anchors = 0;
    boolean confidentLocality =
        localityResolver.scan(q).stream().anyMatch(m -> m.confidence() >= LocalityResolver.CONFIDENT);
    if (confidentLocality) {
      anchors++;
    }
    if (BUDGET.matcher(q).find()) {
      anchors++;
    }
    if (RentalVocabulary.explicitRoomType(q) != null) {
      anchors++;
    }
    if (BHK.matcher(q).find()) {
      anchors++;
    }
    return anchors;
  }
}
