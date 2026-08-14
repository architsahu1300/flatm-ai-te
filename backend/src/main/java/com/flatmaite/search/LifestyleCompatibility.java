package com.flatmaite.search;

import com.flatmaite.common.domain.CleanlinessLevel;
import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.DrinkingHabit;
import com.flatmaite.common.domain.GuestFrequency;
import com.flatmaite.common.domain.PartyFrequency;
import com.flatmaite.common.domain.PetsStance;
import com.flatmaite.common.domain.SleepSchedule;
import com.flatmaite.common.domain.SmokingHabit;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.user.Profile;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic pairwise lifestyle compatibility between two user profiles. Each facet scores in
 * [0,1] (unknown = 0.5 neutral); the total is a weighted mean. Pure functions — unit-tested and
 * reused by both flatmate discovery and the AI MatchScorer.
 */
public final class LifestyleCompatibility {

  private LifestyleCompatibility() {}

  public record FacetScore(String facet, double weight, double score) {}

  public record Result(double score, List<FacetScore> facets, List<String> sharedTraits) {}

  public static Result between(Profile a, Profile b) {
    List<FacetScore> facets = new ArrayList<>();
    facets.add(new FacetScore("smoking", 1.5, smoking(a.getSmoking(), b.getSmoking())));
    facets.add(new FacetScore("social", 1.5, social(a.getSocialStyle(), b.getSocialStyle())));
    facets.add(new FacetScore("parties", 1.2, party(a.getPartyFrequency(), b.getPartyFrequency())));
    facets.add(new FacetScore("cleanliness", 1.2, cleanliness(a.getCleanliness(), b.getCleanliness())));
    facets.add(new FacetScore("sleep", 1.0, sleep(a.getSleepSchedule(), b.getSleepSchedule())));
    facets.add(new FacetScore("diet", 1.0, diet(a.getDiet(), b.getDiet())));
    facets.add(new FacetScore("pets", 1.0, pets(a.getPets(), b.getPets())));
    facets.add(new FacetScore("drinking", 0.8, drinking(a.getDrinking(), b.getDrinking())));
    facets.add(new FacetScore("guests", 0.8, guests(a.getGuestFrequency(), b.getGuestFrequency())));

    double weightSum = 0;
    double scoreSum = 0;
    for (FacetScore f : facets) {
      weightSum += f.weight();
      scoreSum += f.weight() * f.score();
    }
    return new Result(weightSum == 0 ? 0.5 : scoreSum / weightSum, facets, sharedTraits(a, b));
  }

  public static List<String> sharedTraits(Profile a, Profile b) {
    List<String> traits = new ArrayList<>();
    if (a.getSocialStyle() == SocialStyle.QUIET && b.getSocialStyle() == SocialStyle.QUIET) {
      traits.add("You both prefer quiet homes");
    }
    if (a.getSmoking() == SmokingHabit.NEVER && b.getSmoking() == SmokingHabit.NEVER) {
      traits.add("Both non-smokers");
    }
    if (a.getDiet() != null && a.getDiet() == b.getDiet()) {
      traits.add(
          switch (a.getDiet()) {
            case VEGETARIAN, JAIN, VEGAN -> "Both vegetarian";
            case EGGETARIAN -> "Similar food habits";
            case NON_VEGETARIAN -> "Both foodies";
          });
    }
    if (a.getSleepSchedule() == SleepSchedule.EARLY_BIRD && b.getSleepSchedule() == SleepSchedule.EARLY_BIRD) {
      traits.add("Both early risers");
    }
    if (a.getSleepSchedule() == SleepSchedule.NIGHT_OWL && b.getSleepSchedule() == SleepSchedule.NIGHT_OWL) {
      traits.add("Both night owls");
    }
    if (a.getCleanliness() == CleanlinessLevel.VERY_TIDY && b.getCleanliness() == CleanlinessLevel.VERY_TIDY) {
      traits.add("Both like a spotless home");
    }
    if (a.getWfhFrequency() != null && a.getWfhFrequency() == b.getWfhFrequency()) {
      switch (a.getWfhFrequency()) {
        case FULL_TIME -> traits.add("Both work from home");
        case HYBRID -> traits.add("Similar working schedules");
        case NEVER -> traits.add("Both work from office");
      }
    }
    return traits.size() > 2 ? traits.subList(0, 2) : traits;
  }

  // --- facet rules (unknown on either side = 0.5) ---

  static double smoking(SmokingHabit a, SmokingHabit b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == SmokingHabit.OCCASIONALLY || b == SmokingHabit.OCCASIONALLY) return 0.6;
    return 0.1; // NEVER vs REGULARLY
  }

  static double social(SocialStyle a, SocialStyle b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == SocialStyle.BALANCED || b == SocialStyle.BALANCED) return 0.7;
    return 0.15; // QUIET vs VERY_SOCIAL
  }

  static double party(PartyFrequency a, PartyFrequency b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == PartyFrequency.OCCASIONALLY || b == PartyFrequency.OCCASIONALLY) return 0.65;
    return 0.1; // NEVER vs FREQUENTLY
  }

  static double cleanliness(CleanlinessLevel a, CleanlinessLevel b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == CleanlinessLevel.AVERAGE || b == CleanlinessLevel.AVERAGE) return 0.6;
    return 0.3; // RELAXED vs VERY_TIDY
  }

  static double sleep(SleepSchedule a, SleepSchedule b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == SleepSchedule.FLEXIBLE || b == SleepSchedule.FLEXIBLE) return 0.8;
    return 0.2; // EARLY_BIRD vs NIGHT_OWL
  }

  static double diet(Diet a, Diet b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    boolean aVeg = a == Diet.VEGETARIAN || a == Diet.JAIN || a == Diet.VEGAN;
    boolean bVeg = b == Diet.VEGETARIAN || b == Diet.JAIN || b == Diet.VEGAN;
    if (aVeg && bVeg) return 0.9;
    if (a == Diet.EGGETARIAN || b == Diet.EGGETARIAN) return 0.7;
    return 0.4; // veg vs non-veg — workable, but real friction
  }

  static double pets(PetsStance a, PetsStance b) {
    if (a == null || b == null) return 0.5;
    boolean aHas = a == PetsStance.HAS_PETS;
    boolean bHas = b == PetsStance.HAS_PETS;
    boolean aNo = a == PetsStance.NO_PETS;
    boolean bNo = b == PetsStance.NO_PETS;
    if ((aHas && bNo) || (bHas && aNo)) return 0.0; // hard conflict
    if (aNo && bNo) return 1.0;
    if (a == b) return 1.0;
    return 0.8;
  }

  static double drinking(DrinkingHabit a, DrinkingHabit b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == DrinkingHabit.SOCIALLY || b == DrinkingHabit.SOCIALLY) return 0.7;
    return 0.35;
  }

  static double guests(GuestFrequency a, GuestFrequency b) {
    if (a == null || b == null) return 0.5;
    if (a == b) return 1.0;
    if (a == GuestFrequency.SOMETIMES || b == GuestFrequency.SOMETIMES) return 0.7;
    return 0.25; // RARELY vs OFTEN
  }
}
