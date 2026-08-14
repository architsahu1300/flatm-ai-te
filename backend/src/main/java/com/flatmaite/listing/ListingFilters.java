package com.flatmaite.listing;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SocialStyle;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * One filter vocabulary for both the traditional explore API and the AI pipeline's hard-filter
 * stage — the "AI chips ↔ filters bridge" on the backend side.
 */
@Builder(toBuilder = true)
public record ListingFilters(
    List<UUID> localityIds,
    Integer budgetMin,
    Integer budgetMax,
    RoomType roomType,
    List<ListingType> listingTypes,
    List<Furnishing> furnishings,
    Integer bhkMin,
    Integer bhkMax,
    LocalDate moveInBy,
    GenderPreference genderPref,
    List<String> amenitySlugs,
    Boolean verifiedOnly,
    Boolean smokeFreeHousehold,
    Boolean petFriendly,
    Boolean vegHousehold,
    SocialStyle householdSocial,
    Boolean couplesAllowed,
    Integer maxDeposit) {

  public static ListingFilters empty() {
    return ListingFilters.builder().build();
  }
}
