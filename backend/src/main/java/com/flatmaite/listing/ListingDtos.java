package com.flatmaite.listing;

import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.PropertyType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SocialStyle;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ListingDtos {

  private ListingDtos() {}

  public record UpsertListingRequest(
      @NotNull ListingType type,
      @Size(min = 5, max = 140) String title,
      @Size(max = 5000) String description,
      @Min(1000) @Max(2000000) Integer rentMonthly,
      @Min(0) Integer deposit,
      @Min(0) Integer maintenanceMonthly,
      LocalDate availableFrom,
      @Min(1) @Max(60) Integer minLeaseMonths,
      @Min(1) @Max(20) Integer maxOccupants,
      Furnishing furnishing,
      Boolean bathroomAttached,
      Boolean balcony,
      GenderPreference preferredGender,
      Boolean couplesAllowed,
      Boolean householdSmoking,
      Boolean householdPets,
      Diet householdDiet,
      SocialStyle householdSocial,
      @Size(max = 500) String occupantsDesc,
      List<String> amenitySlugs,
      // property block
      UUID localityId,
      PropertyType propertyType,
      @Min(0) @Max(10) Integer bhk,
      @Size(max = 255) String addressLine,
      @Size(max = 120) String societyName) {}

  public record StatusRequest(@NotNull ListingStatus status) {}

  public record ImageResponse(UUID id, String url, boolean isCover, int sortOrder) {}

  public record CardResponse(
      UUID id,
      ListingType type,
      ListingStatus status,
      String title,
      int rentMonthly,
      int deposit,
      int maintenanceMonthly,
      String localityName,
      UUID localityId,
      Integer bhk,
      RoomType roomType,
      Furnishing furnishing,
      LocalDate availableFrom,
      String coverImageUrl,
      List<String> amenitySlugs,
      GenderPreference preferredGender,
      SocialStyle householdSocial,
      Boolean householdSmoking,
      Boolean householdPets,
      Diet householdDiet,
      String occupantsDesc,
      boolean listerVerified,
      boolean propertyVerified,
      boolean isBoosted,
      String updatedAt) {}

  public record DetailResponse(
      CardResponse card,
      String description,
      Integer minLeaseMonths,
      Integer maxOccupants,
      Boolean bathroomAttached,
      Boolean balcony,
      Boolean couplesAllowed,
      List<ImageResponse> images,
      List<String> amenityLabels,
      Double approxLat,
      Double approxLng,
      UUID listerId,
      String listerName,
      String listerImage,
      int viewCount) {}

  public record PageResponse<T>(List<T> items, int page, int size, long total) {}
}
