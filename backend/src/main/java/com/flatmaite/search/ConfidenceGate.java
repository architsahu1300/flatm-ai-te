package com.flatmaite.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which slots are enforced as SQL filters and which only rank. A slot the user's words
 * grounded stays a filter; one the reader inferred becomes a preference, so a guess can no longer
 * delete a listing the user would have wanted.
 *
 * <p>Two slots are never softened however doubtful the grade: an exclusion the user typed is a
 * promise, and quietly surfacing unverified listings to someone who asked for verified ones is the
 * opposite of what this product sells. Both are stated outright in practice — the set is a
 * guarantee, not a workaround.
 */
public final class ConfidenceGate {

  public static final double HARD_THRESHOLD = 0.75;
  public static final Set<String> ALWAYS_HARD = Set.of("excludeLocations", "verifiedOnly");

  /** Soft slots an existing score component already ranks — dropping the filter is enough. */
  public static final Set<String> SCORED_ELSEWHERE =
      Set.of("budgetMin", "budgetMax", "maxDeposit", "locations", "commuteTo", "commuteTo.maxMinutes", "lifestyle");

  private ConfidenceGate() {}

  public static boolean isHard(SearchIntent intent, String slot) {
    return ALWAYS_HARD.contains(slot) || intent.confidenceOf(slot) >= HARD_THRESHOLD;
  }

  /** Non-null slots that are not enforced, in {@link SearchIntent#GATED_SLOTS} order. */
  public static List<String> softSlots(SearchIntent intent) {
    List<String> out = new ArrayList<>();
    for (String slot : SearchIntent.GATED_SLOTS) {
      if (isPresent(intent, slot) && !isHard(intent, slot)) {
        out.add(slot);
      }
    }
    return out;
  }

  /** Soft slots with no existing score component — these become the `preferences` component. */
  public static List<String> preferenceSlots(SearchIntent intent) {
    return softSlots(intent).stream().filter(s -> !SCORED_ELSEWHERE.contains(s)).toList();
  }

  static boolean isPresent(SearchIntent intent, String slot) {
    return switch (slot) {
      case "locations" -> intent.locations() != null && !intent.locations().isEmpty();
      case "excludeLocations" -> intent.excludeLocations() != null && !intent.excludeLocations().isEmpty();
      case "budgetMin" -> intent.budgetMin() != null;
      case "budgetMax" -> intent.budgetMax() != null;
      case "maxDeposit" -> intent.maxDeposit() != null;
      case "roomType" -> intent.roomType() != null;
      case "listingTypes" -> intent.listingTypes() != null && !intent.listingTypes().isEmpty();
      case "furnished" -> intent.furnished() != null;
      case "bhk" -> intent.bhk() != null && (intent.bhk().min() != null || intent.bhk().max() != null);
      case "moveInDate" -> intent.moveInDate() != null;
      case "genderPreference" -> intent.genderPreference() != null;
      case "couplesOk" -> intent.couplesOk() != null;
      case "amenities" -> intent.amenities() != null && !intent.amenities().isEmpty();
      case "lifestyle" -> intent.lifestyle() != null;
      case "commuteTo", "commuteTo.maxMinutes" -> intent.commuteTo() != null;
      case "verifiedOnly" -> intent.verifiedOnly() != null;
      default -> false;
    };
  }

  /** Display label for a slot, used in notes and near-miss reasons. */
  public static String label(String slot) {
    return switch (slot) {
      case "locations" -> "area";
      case "excludeLocations" -> "excluded areas";
      case "budgetMin" -> "minimum budget";
      case "budgetMax" -> "budget";
      case "maxDeposit" -> "deposit";
      case "roomType" -> "room type";
      case "listingTypes" -> "listing type";
      case "furnished" -> "furnishing";
      case "bhk" -> "size";
      case "moveInDate" -> "move-in date";
      case "genderPreference" -> "gender preference";
      case "couplesOk" -> "couples";
      case "amenities" -> "amenities";
      case "lifestyle" -> "lifestyle";
      case "commuteTo" -> "commute";
      case "commuteTo.maxMinutes" -> "commute time";
      case "verifiedOnly" -> "verified only";
      default -> slot;
    };
  }
}
