package com.flatmaite.search;

import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides whether a follow-up message continues the current search or starts a new one.
 *
 * <p>Everything typed into an active session used to be merged with the prior intent, so a complete
 * new request ("single sharing room chahiye powai me budget 40k hai") silently inherited whatever
 * the previous search had set — most damagingly the old locality, which produces zero results and
 * makes the search look broken when widening the area then reveals obvious matches.
 *
 * <p>The rule counts independent anchors. Real refinements lean on the prior intent and carry at
 * most one ("cheaper", "make it 30k", "same in Andheri"); a self-contained request carries several.
 */
@Component
@RequiredArgsConstructor
public class NewQueryDetector {

  private static final int SELF_CONTAINED_ANCHORS = 2;

  private static final Pattern BUDGET =
      Pattern.compile("(\\d+(?:\\.\\d+)?\\s*k\\b)|(\\d{4,7})|(\\d+(?:\\.\\d+)?\\s*(?:lakh|lac)\\b)");
  private static final Pattern BHK = Pattern.compile("\\d\\s*bhk");
  private static final Pattern HOUSING_NOUN =
      Pattern.compile("\\b(flatmate|roommate|pg|paying guest|apartment|flat|room|studio|1rk)\\b");

  private final LocalityResolver localityResolver;

  /** True when the message stands on its own and should replace the session's intent. */
  public boolean isSelfContained(String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    String q = query.toLowerCase(java.util.Locale.ROOT);
    int anchors = 0;
    if (!localityResolver.scan(q).isEmpty()) {
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
    // a housing noun alone proves nothing, but it corroborates the anchors above
    if (anchors >= SELF_CONTAINED_ANCHORS && HOUSING_NOUN.matcher(q).find()) {
      return true;
    }
    // three hard anchors are self-contained even without an explicit noun
    return anchors >= 3;
  }
}
