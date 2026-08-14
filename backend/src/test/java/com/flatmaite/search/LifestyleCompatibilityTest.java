package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.CleanlinessLevel;
import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.PetsStance;
import com.flatmaite.common.domain.SleepSchedule;
import com.flatmaite.common.domain.SmokingHabit;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.user.Profile;
import org.junit.jupiter.api.Test;

class LifestyleCompatibilityTest {

  private Profile profile(SocialStyle social, SmokingHabit smoking, Diet diet, SleepSchedule sleep) {
    return Profile.builder()
        .socialStyle(social)
        .smoking(smoking)
        .diet(diet)
        .sleepSchedule(sleep)
        .build();
  }

  @Test
  void identicalLifestyles_scoreNearPerfect() {
    Profile a = profile(SocialStyle.QUIET, SmokingHabit.NEVER, Diet.VEGETARIAN, SleepSchedule.EARLY_BIRD);
    Profile b = profile(SocialStyle.QUIET, SmokingHabit.NEVER, Diet.VEGETARIAN, SleepSchedule.EARLY_BIRD);

    LifestyleCompatibility.Result result = LifestyleCompatibility.between(a, b);

    // known facets are all 1.0; unknown facets contribute 0.5 — must land well above neutral
    assertThat(result.score()).isGreaterThanOrEqualTo(0.75);
    assertThat(result.sharedTraits()).contains("You both prefer quiet homes");
  }

  @Test
  void opposedLifestyles_scoreLow() {
    Profile quiet = profile(SocialStyle.QUIET, SmokingHabit.NEVER, Diet.VEGETARIAN, SleepSchedule.EARLY_BIRD);
    Profile partier = profile(SocialStyle.VERY_SOCIAL, SmokingHabit.REGULARLY, Diet.NON_VEGETARIAN, SleepSchedule.NIGHT_OWL);

    LifestyleCompatibility.Result result = LifestyleCompatibility.between(quiet, partier);

    assertThat(result.score()).isLessThan(0.45);
    assertThat(result.sharedTraits()).isEmpty();
  }

  @Test
  void unknownFacets_areNeutral() {
    LifestyleCompatibility.Result result =
        LifestyleCompatibility.between(Profile.builder().build(), Profile.builder().build());
    assertThat(result.score()).isEqualTo(0.5);
  }

  @Test
  void petOwner_vs_noPets_isHardConflict() {
    assertThat(LifestyleCompatibility.pets(PetsStance.HAS_PETS, PetsStance.NO_PETS)).isZero();
    assertThat(LifestyleCompatibility.pets(PetsStance.HAS_PETS, PetsStance.LOVES_PETS)).isGreaterThan(0.5);
  }

  @Test
  void flexibleSleeper_bridgesExtremes() {
    assertThat(LifestyleCompatibility.sleep(SleepSchedule.FLEXIBLE, SleepSchedule.NIGHT_OWL)).isEqualTo(0.8);
    assertThat(LifestyleCompatibility.sleep(SleepSchedule.EARLY_BIRD, SleepSchedule.NIGHT_OWL)).isEqualTo(0.2);
  }

  @Test
  void cleanlinessMatters() {
    assertThat(
            LifestyleCompatibility.cleanliness(CleanlinessLevel.VERY_TIDY, CleanlinessLevel.RELAXED))
        .isLessThan(
            LifestyleCompatibility.cleanliness(CleanlinessLevel.VERY_TIDY, CleanlinessLevel.AVERAGE));
  }
}
