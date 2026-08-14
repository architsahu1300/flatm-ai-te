package com.flatmaite.flatmate;

import com.flatmaite.common.domain.GenderPreference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FlatmateDtos {

  private FlatmateDtos() {}

  public record UpsertFlatmateProfileRequest(
      @NotBlank @Size(min = 10, max = 140) String headline,
      @Size(max = 3000) String about,
      Boolean hasFlat,
      Integer budgetMin,
      Integer budgetMax,
      List<UUID> localityIds,
      LocalDate moveInFrom,
      GenderPreference genderPref) {}

  public record CardResponse(
      UUID id,
      UUID userId,
      String name,
      String image,
      Integer age,
      String gender,
      String occupation,
      String occupationDetail,
      String headline,
      boolean hasFlat,
      Integer budgetMin,
      Integer budgetMax,
      List<String> localityNames,
      LocalDate moveInFrom,
      List<String> lifestyleTags,
      boolean idVerified,
      Integer compatibility,
      List<String> sharedTraits) {}

  public record DetailResponse(
      CardResponse card,
      String about,
      String bio,
      String companyOrCollege,
      List<String> languages,
      String smoking,
      String drinking,
      String diet,
      String pets,
      String sleepSchedule,
      String wfhFrequency,
      String cleanliness,
      String socialStyle,
      String partyFrequency,
      String guestFrequency,
      String cookingFrequency) {}
}
