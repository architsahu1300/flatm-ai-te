package com.flatmaite.user;

import com.flatmaite.common.domain.CleanlinessLevel;
import com.flatmaite.common.domain.CookingFrequency;
import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.DrinkingHabit;
import com.flatmaite.common.domain.Gender;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.GuestFrequency;
import com.flatmaite.common.domain.OccupationType;
import com.flatmaite.common.domain.PartyFrequency;
import com.flatmaite.common.domain.PetsStance;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SleepSchedule;
import com.flatmaite.common.domain.SmokingHabit;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.common.domain.WfhFrequency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class UserDtos {

  private UserDtos() {}

  public record UpdateMeRequest(@Size(min = 1, max = 120) String name, String image) {}

  public record ProfileRequest(
      LocalDate dateOfBirth,
      Gender gender,
      OccupationType occupation,
      @Size(max = 120) String occupationDetail,
      @Size(max = 120) String companyOrCollege,
      List<String> languages,
      @Size(max = 2000) String bio,
      UUID currentLocalityId,
      @Size(max = 120) String hometown,
      SmokingHabit smoking,
      DrinkingHabit drinking,
      Diet diet,
      PetsStance pets,
      SleepSchedule sleepSchedule,
      WfhFrequency wfhFrequency,
      CleanlinessLevel cleanliness,
      SocialStyle socialStyle,
      PartyFrequency partyFrequency,
      GuestFrequency guestFrequency,
      CookingFrequency cookingFrequency,
      @Size(max = 500) String householdPref) {}

  public record PreferencesRequest(
      @Min(1000) @Max(1000000) Integer budgetMin,
      @Min(1000) @Max(1000000) Integer budgetMax,
      List<UUID> localityIds,
      LocalDate moveInFrom,
      LocalDate moveInTo,
      @Min(1) @Max(60) Integer leaseMonthsMin,
      @Min(1) @Max(60) Integer leaseMonthsMax,
      RoomType roomType,
      List<String> furnishing,
      @Min(0) @Max(10) Integer bhkMin,
      @Min(0) @Max(10) Integer bhkMax,
      @Min(0) Integer depositMax,
      Boolean parkingNeeded,
      GenderPreference genderPref,
      List<String> amenities,
      @Size(max = 1000) String notes) {}
}
