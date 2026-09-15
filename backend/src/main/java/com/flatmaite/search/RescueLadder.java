package com.flatmaite.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What to try, in order, when the hard-filtered page comes back thin. The widest honest move first —
 * looking further out — and only then giving up a filter, least-confident first, because the
 * constraint the reader was least sure of is the one the user will miss least. Promises
 * ({@link ConfidenceGate#ALWAYS_HARD}) are never on the ladder: a short page is better than a
 * dishonest one.
 */
public final class RescueLadder {

  /** @param slot the filter this rung gives up, or null for the wider-ring rung. */
  public record Rung(String slot, SearchIntent intent, Integer radiusMinutes, String reason) {}

  private RescueLadder() {}

  public static List<Rung> rungs(SearchIntent intent, int rescueRadiusMinutes) {
    List<Rung> out = new ArrayList<>();
    boolean hasPlace =
        ConfidenceGate.isPresent(intent, "locations") || ConfidenceGate.isPresent(intent, "commuteTo");
    if (hasPlace) {
      out.add(new Rung(null, intent, rescueRadiusMinutes, "further out"));
    }
    List<String> droppable =
        SearchIntent.GATED_SLOTS.stream()
            .filter(s -> !ConfidenceGate.ALWAYS_HARD.contains(s))
            .filter(s -> ConfidenceGate.isPresent(intent, s))
            .filter(s -> ConfidenceGate.isHard(intent, s))
            .sorted(
                Comparator.comparingDouble(intent::confidenceOf)
                    .thenComparingInt(SearchIntent.GATED_SLOTS::indexOf))
            .toList();
    for (String slot : droppable) {
      out.add(new Rung(slot, without(intent, slot), null, ConfidenceGate.label(slot)));
    }
    return out;
  }

  /** The same intent with one slot cleared — everything else still enforced. */
  static SearchIntent without(SearchIntent intent, String slot) {
    SearchIntent.SearchIntentBuilder b = intent.toBuilder();
    switch (slot) {
      case "locations" -> b.locations(null);
      case "budgetMin" -> b.budgetMin(null);
      case "budgetMax" -> b.budgetMax(null);
      case "maxDeposit" -> b.maxDeposit(null);
      case "roomType" -> b.roomType(null);
      case "listingTypes" -> b.listingTypes(null);
      case "furnished" -> b.furnished(null);
      case "bhk" -> b.bhk(null);
      case "moveInDate" -> b.moveInDate(null);
      case "genderPreference" -> b.genderPreference(null);
      case "couplesOk" -> b.couplesOk(null);
      case "amenities" -> b.amenities(null);
      case "lifestyle" -> b.lifestyle(null);
      case "commuteTo", "commuteTo.maxMinutes" -> b.commuteTo(null);
      default -> { /* ALWAYS_HARD and unknown slots are never dropped */ }
    }
    return b.build();
  }
}
