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
 *
 * <p>{@code geoRings} and {@code geoExemptLocalityIds} are the per-property half of a radius
 * search (spec §4.1, ruling R14). {@code localityIds} admits whole localities by centroid, which
 * is too coarse for a heading that names a distance: a property at the far edge of a locality
 * whose centroid sits 4.9 km away can itself be 6.3 km away. The rings hold each such property to
 * the figure; the exemptions keep the places the user actually named unconditional.
 */
@Builder(toBuilder = true)
public record ListingFilters(
    List<UUID> localityIds,
    List<UUID> excludeLocalityIds,
    List<GeoRing> geoRings,
    List<UUID> geoExemptLocalityIds,
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

  /**
   * One ring a property's own point must fall inside, centred on a locality centroid.
   *
   * <p>{@code meters} is great-circle distance — the same straight-line quantity {@code
   * CommuteEstimator} measures between centroids and the API reports as {@code distanceKm} — so
   * the ring and the figure a row prints beside it can never disagree about where that row is.
   */
  public record GeoRing(double lat, double lng, double meters) {}

  public static ListingFilters empty() {
    return ListingFilters.builder().build();
  }
}
