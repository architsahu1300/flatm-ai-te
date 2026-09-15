package com.flatmaite.search;

import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.flatmate.FlatmateProfile;
import com.flatmaite.listing.Listing;
import com.flatmaite.search.SearchIntent.Lifestyle;
import com.flatmaite.user.Profile;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, transparent Match Score. Weights renormalize over the components that apply to
 * this intent, every component carries a human-readable detail line, and the LLM explanation layer
 * is only ever allowed to cite these facts. Pure functions — no Spring, fully unit-tested.
 */
public final class MatchScorer {

  private MatchScorer() {}

  /** Location score for anything admitted from the surrounding radius never drops below this. */
  public static final double MIN_LOCATION_SCORE = 0.3;

  public record Component(String component, double weight, double score, String detail) {}

  public record Scored(int matchScore, List<Component> breakdown) {}

  public record ListingCandidate(
      Listing listing,
      UUID localityId,
      String localityName,
      boolean emailVerified,
      boolean phoneVerified,
      boolean idOrPropertyVerified,
      HybridRetriever.Retrieval retrieval,
      Integer commuteMinutes,
      String anchorName,
      boolean anchorIsCommute,
      int radiusMinutes,
      boolean inPreferredLocality) {}

  public record FlatmateCandidate(
      FlatmateProfile flatmate,
      Profile profile,
      boolean emailVerified,
      boolean phoneVerified,
      boolean idVerified,
      HybridRetriever.Retrieval retrieval,
      double locationOverlap,
      double profileCompleteness) {}

  // ------------------------------------------------------------------ properties

  public static Scored scoreListing(SearchIntent intent, ListingCandidate c) {
    List<Component> parts = new ArrayList<>();
    Listing l = c.listing();

    // budgetFit (.20) — applies when a budget cap exists
    if (intent.budgetMax() != null) {
      double score;
      String detail;
      int rent = l.getRentMonthly();
      int max = intent.budgetMax();
      if (rent <= max) {
        score = 1.0;
        detail = rent <= max - 1000
            ? "₹%,d is ₹%,d under your ₹%,d budget".formatted(rent, max - rent, max)
            : "₹%,d fits your ₹%,d budget".formatted(rent, max);
        if (rent < max * 0.35) {
          score = 0.7; // suspiciously cheap — scam-adjacent signal
          detail = "₹%,d is unusually low for this area — verify carefully".formatted(rent);
        }
      } else {
        score = Math.max(0, 1.0 - (rent - max) / (0.25 * max));
        detail = "₹%,d is ₹%,d over your ₹%,d budget".formatted(rent, rent - max, max);
      }
      parts.add(new Component("budgetFit", 0.20, score, detail));
    }

    // location (.20) — applies when the intent has any location signal. In a requested locality
    // → 1.0; anything admitted from the surrounding radius decays with distance to the nearest
    // requested locality (or the commute anchor), floored so nearby never reads as "wrong".
    boolean hasLocationSignal =
        (intent.locations() != null && !intent.locations().isEmpty()) || intent.commuteTo() != null;
    if (hasLocationSignal) {
      double score;
      String detail;
      if (c.inPreferredLocality()) {
        score = 1.0;
        detail = "In %s — one of your preferred areas".formatted(c.localityName());
      } else if (c.commuteMinutes() != null) {
        int radius = Math.max(1, c.radiusMinutes());
        score = Math.max(MIN_LOCATION_SCORE, 1.0 - c.commuteMinutes() / (2.0 * radius));
        detail =
            "~%d min %s %s (estimate)"
                .formatted(c.commuteMinutes(), c.anchorIsCommute() ? "to" : "from", c.anchorName());
      } else {
        score = MIN_LOCATION_SCORE;
        detail = "Outside your preferred areas";
      }
      parts.add(new Component("location", 0.20, score, detail));
    }

    // lifestyleCompat (.20) — applies when the intent mentions lifestyle
    Lifestyle wants = intent.lifestyleOrEmpty();
    List<Component> facets = listingLifestyleFacets(wants, l);
    if (!facets.isEmpty()) {
      double mean = facets.stream().mapToDouble(Component::score).average().orElse(0.5);
      String detail =
          facets.stream()
              .map(Component::detail)
              .filter(d -> d != null && !d.isBlank())
              .reduce((a, b) -> a + "; " + b)
              .orElse("");
      parts.add(new Component("lifestyle", 0.20, mean, detail));
    }

    // relevance (.15) — always applies: every candidate came from at least one retrieval ranking,
    // so there is never a missing component whose weight could flow to the others
    parts.add(
        new Component(
            "relevance", 0.15, clamp01(c.retrieval().score()), listingRelevanceDetail(c.retrieval())));

    // verification (.10) — always applies
    double verification =
        0.4 * (c.emailVerified() ? 1 : 0)
            + 0.3 * (c.phoneVerified() ? 1 : 0)
            + 0.3 * (c.idOrPropertyVerified() ? 1 : 0);
    parts.add(
        new Component(
            "verification",
            0.10,
            verification,
            c.idOrPropertyVerified() ? "Verified lister" : "Lister not fully verified yet"));

    // listing quality (.10)
    parts.add(
        new Component(
            "quality",
            0.10,
            clamp01(l.getQualityScore()),
            l.getQualityScore() >= 0.7 ? "Detailed listing with photos" : "Listing is a bit sparse"));

    // freshness (.05)
    double ageDays =
        l.getUpdatedAt() == null
            ? 0
            : Duration.between(l.getUpdatedAt(), Instant.now()).toHours() / 24.0;
    parts.add(new Component("freshness", 0.05, Math.exp(-ageDays / 14.0), null));

    // availability alignment: soft component when the intent has a move-in date
    if (intent.moveInDate() != null) {
      try {
        LocalDate wanted = LocalDate.parse(intent.moveInDate());
        long gap = Math.abs(l.getAvailableFrom().toEpochDay() - wanted.toEpochDay());
        double score = gap <= 7 ? 1.0 : clamp01(1.0 - (gap - 7) / 45.0);
        parts.add(
            new Component(
                "availability",
                0.10,
                score,
                gap <= 7
                    ? "Available right around your move-in date"
                    : "Available %s".formatted(l.getAvailableFrom())));
      } catch (Exception ignored) {
        // unparseable date — skip component
      }
    }

    // preferences (.15) — slots the reader inferred rather than read. They no longer filter, so they
    // rank here instead: a listing that misses every one of them still appears, just lower.
    List<String> preferenceSlots = ConfidenceGate.preferenceSlots(intent);
    if (!preferenceSlots.isEmpty()) {
      List<String> met = new ArrayList<>();
      List<String> missed = new ArrayList<>();
      for (String slot : preferenceSlots) {
        (satisfies(intent, l, slot) ? met : missed).add(ConfidenceGate.label(slot));
      }
      double score = met.size() / (double) preferenceSlots.size();
      String detail =
          missed.isEmpty()
              ? "Matches your preferred %s".formatted(String.join(", ", met))
              : "%s — a preference, not a requirement".formatted(capitalize(String.join(", ", missed)));
      parts.add(new Component("preferences", 0.15, score, detail));
    }

    return finish(parts);
  }

  /**
   * Does this listing satisfy a soft slot? Unknown or unobservable slots count as satisfied — never
   * penalise a listing for something this path cannot check. {@code bhk} and {@code amenities} are
   * not reachable from {@link Listing} on this path (no loaded {@code Property}/association), and
   * {@code moveInDate} is already ranked by the unconditional {@code availability} component above,
   * so scoring it again here would double-count the same signal.
   */
  private static boolean satisfies(SearchIntent intent, Listing l, String slot) {
    return switch (slot) {
      case "roomType" -> l.getRoomType() == intent.roomType();
      case "furnished" -> l.getFurnishing() == intent.furnished();
      case "listingTypes" -> intent.listingTypes().contains(l.getType());
      case "genderPreference" -> l.getPreferredGender() == GenderPreference.ANY
          || l.getPreferredGender() == intent.genderPreference();
      case "couplesOk" -> !Boolean.TRUE.equals(intent.couplesOk()) || l.isCouplesAllowed();
      case "bhk", "amenities", "moveInDate" -> true; // not observable on this path — never penalised
      default -> true;
    };
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static List<Component> listingLifestyleFacets(Lifestyle wants, Listing l) {
    List<Component> facets = new ArrayList<>();
    if (Boolean.TRUE.equals(wants.quiet())) {
      double s =
          l.getHouseholdSocial() == null
              ? 0.5
              : switch (l.getHouseholdSocial()) {
                case QUIET -> 1.0;
                case BALANCED -> 0.55;
                case VERY_SOCIAL -> 0.05;
              };
      facets.add(
          new Component(
              "quiet",
              1,
              s,
              s >= 0.9
                  ? "Quiet household — exactly what you asked for"
                  : s <= 0.2 ? "This is a social, lively flat" : "Household vibe is balanced"));
    }
    if ("NO_SMOKERS".equals(wants.smoking())) {
      double s = l.getHouseholdSmoking() == null ? 0.5 : (l.getHouseholdSmoking() ? 0.0 : 1.0);
      facets.add(
          new Component(
              "smoking",
              1,
              s,
              s >= 0.9 ? "No smoking in the flat" : s <= 0.1 ? "Smoking is allowed here" : null));
    }
    if ("NO_PETS".equals(wants.pets())) {
      double s = l.getHouseholdPets() == null ? 0.5 : (l.getHouseholdPets() ? 0.1 : 1.0);
      facets.add(new Component("pets", 1, s, s <= 0.2 ? "Current flatmates have a pet" : null));
    } else if ("PET_FRIENDLY".equals(wants.pets()) || "HAS_PETS".equals(wants.pets())) {
      double s = l.getHouseholdPets() == null ? 0.5 : (l.getHouseholdPets() ? 1.0 : 0.4);
      facets.add(new Component("pets", 1, s, s >= 0.9 ? "Pet-friendly home" : null));
    }
    if ("VEGETARIAN".equals(wants.diet())) {
      double s =
          l.getHouseholdDiet() == null
              ? 0.5
              : (l.getHouseholdDiet() == Diet.VEGETARIAN
                      || l.getHouseholdDiet() == Diet.JAIN
                      || l.getHouseholdDiet() == Diet.VEGAN)
                  ? 1.0
                  : 0.15;
      facets.add(
          new Component(
              "diet", 1, s, s >= 0.9 ? "Vegetarian household" : s <= 0.2 ? "Non-veg kitchen" : null));
    }
    if (Boolean.FALSE.equals(wants.partiesOk()) || Boolean.TRUE.equals(wants.wfh())) {
      double s =
          l.getHouseholdSocial() == null
              ? 0.5
              : l.getHouseholdSocial() == SocialStyle.VERY_SOCIAL ? 0.15 : 1.0;
      facets.add(
          new Component(
              "parties", 1, s, s <= 0.2 ? "Flatmates host parties fairly often" : null));
    }
    return facets;
  }

  // ------------------------------------------------------------------ flatmates

  public static Scored scoreFlatmate(SearchIntent intent, Profile viewer, FlatmateCandidate c) {
    List<Component> parts = new ArrayList<>();

    // pairwise lifestyle (.30) — needs the viewer's profile
    if (viewer != null && c.profile() != null) {
      LifestyleCompatibility.Result compat = LifestyleCompatibility.between(viewer, c.profile());
      String traits = String.join("; ", compat.sharedTraits());
      parts.add(
          new Component(
              "lifestyle", 0.30, compat.score(), traits.isBlank() ? "Lifestyle profiles compared" : traits));
    } else if (intentWantsLifestyle(intent) && c.profile() != null) {
      // anonymous searcher with lifestyle asks — score intent vs candidate profile
      double s = intentVsProfile(intent.lifestyleOrEmpty(), c.profile());
      parts.add(new Component("lifestyle", 0.30, s, intentVsProfileDetail(intent.lifestyleOrEmpty(), c.profile())));
    }

    // relevance (.20) — always applies, see scoreListing
    parts.add(
        new Component(
            "relevance", 0.20, clamp01(c.retrieval().score()), flatmateRelevanceDetail(c.retrieval())));

    // budget overlap (.15)
    if (intent.budgetMax() != null && c.flatmate().getBudgetMax() != null) {
      int aMin = intent.budgetMin() == null ? 0 : intent.budgetMin();
      int aMax = intent.budgetMax();
      int bMin = c.flatmate().getBudgetMin() == null ? 0 : c.flatmate().getBudgetMin();
      int bMax = c.flatmate().getBudgetMax();
      int overlap = Math.min(aMax, bMax) - Math.max(aMin, bMin);
      int shorter = Math.max(1, Math.min(aMax - aMin, bMax - bMin));
      double s = clamp01((double) overlap / shorter);
      parts.add(
          new Component(
              "budget",
              0.15,
              s,
              s >= 0.6 ? "Similar budgets (₹%,d–₹%,d)".formatted(bMin, bMax) : "Budgets only partly overlap"));
    }

    // location overlap (.15)
    if (intent.locations() != null && !intent.locations().isEmpty()) {
      parts.add(
          new Component(
              "location",
              0.15,
              c.locationOverlap(),
              c.locationOverlap() >= 0.5 ? "Looking in the same areas" : "Different preferred areas"));
    }

    // verification (.10)
    double verification =
        0.4 * (c.emailVerified() ? 1 : 0) + 0.3 * (c.phoneVerified() ? 1 : 0) + 0.3 * (c.idVerified() ? 1 : 0);
    parts.add(
        new Component(
            "verification", 0.10, verification, c.idVerified() ? "ID-verified member" : null));

    // freshness (.05)
    double ageDays =
        c.flatmate().getUpdatedAt() == null
            ? 0
            : Duration.between(c.flatmate().getUpdatedAt(), Instant.now()).toHours() / 24.0;
    parts.add(new Component("freshness", 0.05, Math.exp(-ageDays / 14.0), null));

    // profile completeness (.05)
    parts.add(
        new Component(
            "completeness",
            0.05,
            clamp01(c.profileCompleteness()),
            c.profileCompleteness() >= 0.7 ? "Detailed lifestyle profile" : null));

    return finish(parts);
  }

  private static boolean intentWantsLifestyle(SearchIntent intent) {
    Lifestyle w = intent.lifestyleOrEmpty();
    return w.quiet() != null || w.smoking() != null || w.diet() != null || w.pets() != null;
  }

  private static double intentVsProfile(Lifestyle wants, Profile p) {
    List<Double> scores = new ArrayList<>();
    if (Boolean.TRUE.equals(wants.quiet())) {
      scores.add(
          p.getSocialStyle() == null
              ? 0.5
              : switch (p.getSocialStyle()) {
                case QUIET -> 1.0;
                case BALANCED -> 0.6;
                case VERY_SOCIAL -> 0.1;
              });
    }
    if ("NO_SMOKERS".equals(wants.smoking())) {
      scores.add(
          p.getSmoking() == null
              ? 0.5
              : switch (p.getSmoking()) {
                case NEVER -> 1.0;
                case OCCASIONALLY -> 0.5;
                case REGULARLY -> 0.05;
              });
    }
    if ("VEGETARIAN".equals(wants.diet())) {
      scores.add(
          p.getDiet() == null
              ? 0.5
              : (p.getDiet() == Diet.VEGETARIAN || p.getDiet() == Diet.JAIN || p.getDiet() == Diet.VEGAN)
                  ? 1.0
                  : 0.3);
    }
    return scores.isEmpty() ? 0.5 : scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
  }

  private static String intentVsProfileDetail(Lifestyle wants, Profile p) {
    List<String> hits = new ArrayList<>();
    if (Boolean.TRUE.equals(wants.quiet()) && p.getSocialStyle() == SocialStyle.QUIET) {
      hits.add("Prefers a quiet home");
    }
    if ("NO_SMOKERS".equals(wants.smoking())
        && p.getSmoking() == com.flatmaite.common.domain.SmokingHabit.NEVER) {
      hits.add("Non-smoker");
    }
    if ("VEGETARIAN".equals(wants.diet())
        && p.getDiet() != null
        && (p.getDiet() == Diet.VEGETARIAN || p.getDiet() == Diet.JAIN || p.getDiet() == Diet.VEGAN)) {
      hits.add("Vegetarian");
    }
    if (p.getOccupation() == com.flatmaite.common.domain.OccupationType.WORKING_PROFESSIONAL) {
      hits.add("Working professional");
    }
    return hits.isEmpty() ? "Compared against what you asked for" : String.join("; ", hits);
  }

  /**
   * Detail text for the relevance component, stated only when a ranking actually matched the
   * candidate. With neither hit there is nothing true to say — {@code semanticHit == false} also
   * covers a listing whose own embedding is missing, not just a provider outage — and a null detail
   * is never cited by the explainer (see {@link #positiveDetails}).
   */
  static String listingRelevanceDetail(HybridRetriever.Retrieval r) {
    if (r.semanticHit() && r.lexicalHit()) {
      return "Matches your description on both wording and meaning";
    }
    if (r.semanticHit()) {
      return "Description matches what you asked for";
    }
    if (r.lexicalHit()) {
      return "Mentions the specific things you asked for";
    }
    return null;
  }

  static String flatmateRelevanceDetail(HybridRetriever.Retrieval r) {
    if (r.semanticHit() && r.lexicalHit()) {
      return "Matches your description on both wording and meaning";
    }
    if (r.semanticHit()) {
      return "Their profile matches your description";
    }
    if (r.lexicalHit()) {
      return "Their profile mentions what you asked for";
    }
    return null;
  }

  // ------------------------------------------------------------------ shared

  /** Renormalizes weights over the components that apply, rounds to a 0–100 score. */
  private static Scored finish(List<Component> parts) {
    double weightSum = parts.stream().mapToDouble(Component::weight).sum();
    double total = 0;
    for (Component p : parts) {
      total += (p.weight() / weightSum) * p.score();
    }
    return new Scored((int) Math.round(total * 100), List.copyOf(parts));
  }

  /** Components whose detail reads as a positive reason (score high). */
  public static List<String> positiveDetails(Scored scored) {
    return scored.breakdown().stream()
        .filter(cmp -> cmp.score() >= 0.75 && cmp.detail() != null && !cmp.detail().isBlank())
        .map(Component::detail)
        .toList();
  }

  /** Components whose detail reads as a concern (score low). */
  public static List<String> concernDetails(Scored scored) {
    return scored.breakdown().stream()
        .filter(cmp -> cmp.score() <= 0.35 && cmp.detail() != null && !cmp.detail().isBlank())
        .filter(cmp -> !Set.of("freshness", "relevance", "completeness").contains(cmp.component()))
        .map(Component::detail)
        .toList();
  }

  static double clamp01(double v) {
    return Math.max(0, Math.min(1, v));
  }
}
