package com.flatmaite.search;

import com.flatmaite.common.config.FlatmaiteProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * What to try, in order, when the requested placement comes back thin. Exactly two things relax and
 * both are stated on the page: first distance — moving the map is the smallest thing to give up —
 * and only then a +10% budget band. Every other filter the reader extracted is enforced in every
 * tier, so a filter is either enforced or visibly relaxed and never quietly dropped. Giving one up
 * is a thing the user does by clicking a relaxer ({@link #without}), not something the system does
 * behind their back.
 */
public final class RescueLadder {

  /**
   * Which block of the page a result belongs to. Declared in the ladder's own order — nearby under
   * budget before nearby slightly over budget — which is also the order the page presents them in,
   * so callers may sort on it.
   */
  public enum SearchTier {
    EXACT,
    NEARBY,
    OVER_BUDGET
  }

  /**
   * @param intent what to retrieve with — the user's own intent, except that {@code OVER_BUDGET}
   *     carries the +10% band that the block's own heading names.
   * @param radiusKm how far out this tier reaches, in straight-line kilometres; 0 means the
   *     requested placement only.
   */
  public record Tier(SearchTier tier, SearchIntent intent, double radiusKm) {}

  private RescueLadder() {}

  /**
   * The ladder for this search: the requested placement first, then its neighbourhood, then the
   * labelled band just above the budget. A tier only exists when it could mean something — there is
   * no distance tier without somewhere to measure from, and no band without a budget to exceed.
   *
   * <p>Tiers 1 and 2 carry {@code budgetMax} exactly as the user stated it. The ×1.1 headroom that
   * used to sit inside every query is tier 3 and nothing else (spec §4.5), so a block headed as
   * within budget holds only listings within it.
   */
  public static List<Tier> tiers(
      SearchIntent intent, Placement placement, FlatmaiteProperties.Search props) {
    List<Tier> out = new ArrayList<>();
    out.add(new Tier(SearchTier.EXACT, intent, 0.0));
    if (placement.placed()) {
      out.add(new Tier(SearchTier.NEARBY, intent, props.getNearbyRadiusKm()));
    }
    if (intent.budgetMax() != null) {
      SearchIntent band = intent.toBuilder().budgetMax((int) (intent.budgetMax() * 1.1)).build();
      out.add(
          new Tier(SearchTier.OVER_BUDGET, band, placement.placed() ? props.getNearbyRadiusKm() : 0.0));
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
