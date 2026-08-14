package com.flatmaite.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * The single contract between the LLM, SQL hard filters, the deterministic scorer and the UI's
 * editable chips. Null = "not specified". The original natural-language query is always preserved.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchIntent(
    SearchTarget searchTarget,
    List<LocationRef> locations,
    Integer budgetMin,
    Integer budgetMax,
    RoomType roomType,
    List<ListingType> listingTypes,
    Furnishing furnished,
    BhkRange bhk,
    String moveInDate,
    Integer leaseMonths,
    Integer maxDeposit,
    GenderPreference genderPreference,
    Boolean couplesOk,
    List<String> amenities,
    Lifestyle lifestyle,
    CommuteTo commuteTo,
    Boolean verifiedOnly,
    String freeText,
    String originalQuery) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LocationRef(String name, UUID localityId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BhkRange(Integer min, Integer max) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Builder(toBuilder = true)
  public record Lifestyle(
      Boolean quiet,
      String smoking, // NO_SMOKERS | SMOKER_FRIENDLY
      String pets, // HAS_PETS | NO_PETS | PET_FRIENDLY
      String diet, // VEGETARIAN | NON_VEGETARIAN | ANY
      String drinking, // NO | SOCIAL | ANY
      String sleepSchedule, // EARLY_BIRD | NIGHT_OWL
      String cleanliness, // VERY_TIDY | RELAXED
      Boolean wfh,
      Boolean partiesOk) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CommuteTo(String place, UUID localityId, Integer maxMinutes) {}

  public SearchTarget targetOrDefault() {
    return searchTarget == null ? SearchTarget.PROPERTIES : searchTarget;
  }

  public Lifestyle lifestyleOrEmpty() {
    return lifestyle == null ? Lifestyle.builder().build() : lifestyle;
  }
}
