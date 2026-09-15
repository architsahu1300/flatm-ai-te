package com.flatmaite.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

  /**
   * Soft slots an existing score component already ranks — dropping the filter is enough.
   * <ul>
   * <li>{@code budgetMin}, {@code budgetMax}, {@code maxDeposit} → {@code budgetFit}
   * <li>{@code locations}, {@code commuteTo}, {@code commuteTo.maxMinutes} → {@code location}
   * <li>{@code lifestyle} → {@code lifestyle}
   * <li>{@code moveInDate} → {@code availability}
   * </ul>
   */
  public static final Set<String> SCORED_ELSEWHERE =
      Set.of("budgetMin", "budgetMax", "maxDeposit", "locations", "commuteTo", "commuteTo.maxMinutes", "lifestyle", "moveInDate");

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
      case "commuteTo" -> intent.commuteTo() != null;
      // a radius nobody gave us is not a constraint to grade: the anchor is present, the ring is not
      case "commuteTo.maxMinutes" -> intent.commuteTo() != null && intent.commuteTo().maxMinutes() != null;
      case "verifiedOnly" -> intent.verifiedOnly() != null;
      default -> false;
    };
  }

  /**
   * Is this slot's value carried unchanged from the prior turn? A refinement re-grades only what it
   * actually changed — a constraint the user stated earlier and did not repeat keeps the grade it
   * earned, because saying nothing is not the same as taking it back.
   */
  public static boolean sameValue(SearchIntent prior, SearchIntent next, String slot) {
    if (prior == null || next == null) {
      return false;
    }
    return switch (slot) {
      case "locations" -> Objects.equals(prior.locations(), next.locations());
      case "excludeLocations" -> Objects.equals(prior.excludeLocations(), next.excludeLocations());
      case "budgetMin" -> Objects.equals(prior.budgetMin(), next.budgetMin());
      case "budgetMax" -> Objects.equals(prior.budgetMax(), next.budgetMax());
      case "maxDeposit" -> Objects.equals(prior.maxDeposit(), next.maxDeposit());
      case "roomType" -> prior.roomType() == next.roomType();
      case "listingTypes" -> Objects.equals(prior.listingTypes(), next.listingTypes());
      case "furnished" -> prior.furnished() == next.furnished();
      case "bhk" -> Objects.equals(prior.bhk(), next.bhk());
      case "moveInDate" -> Objects.equals(prior.moveInDate(), next.moveInDate());
      case "genderPreference" -> prior.genderPreference() == next.genderPreference();
      case "couplesOk" -> Objects.equals(prior.couplesOk(), next.couplesOk());
      case "amenities" -> Objects.equals(prior.amenities(), next.amenities());
      case "lifestyle" -> Objects.equals(prior.lifestyle(), next.lifestyle());
      case "commuteTo" -> Objects.equals(prior.commuteTo(), next.commuteTo());
      case "commuteTo.maxMinutes" ->
          Objects.equals(
              prior.commuteTo() == null ? null : prior.commuteTo().maxMinutes(),
              next.commuteTo() == null ? null : next.commuteTo().maxMinutes());
      case "verifiedOnly" -> Objects.equals(prior.verifiedOnly(), next.verifiedOnly());
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
