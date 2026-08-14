package com.flatmaite.listing;

import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.storage.StorageProvider;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.ListingDtos.UpsertListingRequest;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ListingService {

  /** Allowed status transitions (owner-initiated). */
  private static final Map<ListingStatus, Set<ListingStatus>> TRANSITIONS =
      Map.of(
          ListingStatus.DRAFT, Set.of(ListingStatus.ACTIVE, ListingStatus.REMOVED),
          ListingStatus.ACTIVE, Set.of(ListingStatus.PAUSED, ListingStatus.RENTED, ListingStatus.REMOVED),
          ListingStatus.PAUSED, Set.of(ListingStatus.ACTIVE, ListingStatus.RENTED, ListingStatus.REMOVED),
          ListingStatus.RENTED, Set.of(ListingStatus.ACTIVE, ListingStatus.REMOVED),
          ListingStatus.EXPIRED, Set.of(ListingStatus.ACTIVE, ListingStatus.REMOVED));

  private final ListingRepository listings;
  private final PropertyRepository properties;
  private final AmenityRepository amenities;
  private final StorageProvider storage;
  private final ListingEmbeddingRefresher embeddingRefresher;

  @Transactional
  public Listing create(AuthPrincipal user, UpsertListingRequest req) {
    if (req.localityId() == null) {
      throw ApiException.badRequest("locality_required", "Pick a locality for the property");
    }
    Property property =
        Property.builder()
            .ownerId(user.userId())
            .localityId(req.localityId())
            .addressLine(req.addressLine() == null ? "Not shared publicly" : req.addressLine())
            .societyName(req.societyName())
            .propertyType(req.propertyType() == null ? com.flatmaite.common.domain.PropertyType.APARTMENT : req.propertyType())
            .bhk(req.bhk() == null ? 1 : req.bhk().shortValue())
            .build();
    property = properties.save(property);

    Listing listing =
        Listing.builder()
            .propertyId(property.getId())
            .listerId(user.userId())
            .type(req.type())
            .status(ListingStatus.DRAFT)
            .title(req.title() == null ? "Untitled listing" : req.title())
            .description(req.description() == null ? "" : req.description())
            .rentMonthly(req.rentMonthly() == null ? 0 : req.rentMonthly())
            .deposit(req.deposit() == null ? 0 : req.deposit())
            .maintenanceMonthly(req.maintenanceMonthly() == null ? 0 : req.maintenanceMonthly())
            .availableFrom(req.availableFrom() == null ? LocalDate.now() : req.availableFrom())
            .roomType(roomTypeFor(req.type()))
            .build();
    applyOptionalFields(listing, req);
    listing = listings.save(listing);
    return listing;
  }

  @Transactional
  public Listing update(AuthPrincipal user, UUID listingId, UpsertListingRequest req) {
    Listing listing = ownedListing(user, listingId);
    if (req.title() != null) listing.setTitle(req.title());
    if (req.description() != null) listing.setDescription(req.description());
    if (req.rentMonthly() != null) listing.setRentMonthly(req.rentMonthly());
    if (req.deposit() != null) listing.setDeposit(req.deposit());
    if (req.maintenanceMonthly() != null) listing.setMaintenanceMonthly(req.maintenanceMonthly());
    if (req.availableFrom() != null) listing.setAvailableFrom(req.availableFrom());
    if (req.type() != null) {
      listing.setType(req.type());
      listing.setRoomType(roomTypeFor(req.type()));
    }
    applyOptionalFields(listing, req);

    if (req.localityId() != null || req.bhk() != null || req.propertyType() != null) {
      Property property =
          properties
              .findById(listing.getPropertyId())
              .orElseThrow(() -> ApiException.notFound("Property not found"));
      if (req.localityId() != null) property.setLocalityId(req.localityId());
      if (req.bhk() != null) property.setBhk(req.bhk().shortValue());
      if (req.propertyType() != null) property.setPropertyType(req.propertyType());
      if (req.addressLine() != null) property.setAddressLine(req.addressLine());
      if (req.societyName() != null) property.setSocietyName(req.societyName());
      properties.save(property);
    }
    recomputeQuality(listing);
    Listing saved = listings.save(listing);
    embeddingRefresher.refresh(saved.getId());
    return saved;
  }

  @Transactional
  public Listing changeStatus(AuthPrincipal user, UUID listingId, ListingStatus target) {
    Listing listing = ownedListing(user, listingId);
    Set<ListingStatus> allowed = TRANSITIONS.getOrDefault(listing.getStatus(), Set.of());
    if (!allowed.contains(target)) {
      throw ApiException.badRequest(
          "invalid_transition",
          "Cannot move a %s listing to %s".formatted(listing.getStatus(), target));
    }
    if (target == ListingStatus.ACTIVE && listing.getImages().isEmpty()) {
      throw ApiException.badRequest("photos_required", "Add at least one photo before publishing");
    }
    if (target == ListingStatus.ACTIVE && listing.getRentMonthly() < 1000) {
      throw ApiException.badRequest("rent_required", "Set the monthly rent before publishing");
    }
    listing.setStatus(target);
    recomputeQuality(listing);
    Listing saved = listings.save(listing);
    embeddingRefresher.refresh(saved.getId());
    return saved;
  }

  @Transactional
  public void softDelete(AuthPrincipal user, UUID listingId) {
    Listing listing = ownedListing(user, listingId);
    listing.setDeletedAt(java.time.Instant.now());
    listing.setStatus(ListingStatus.REMOVED);
    listings.save(listing);
  }

  @Transactional
  public Listing addImage(AuthPrincipal user, UUID listingId, MultipartFile file) {
    Listing listing = ownedListing(user, listingId);
    if (listing.getImages().size() >= 12) {
      throw ApiException.badRequest("too_many_photos", "A listing can have at most 12 photos");
    }
    String url = storage.store(file, "listings/" + listingId);
    ListingImage image =
        ListingImage.builder()
            .url(url)
            .sortOrder((short) listing.getImages().size())
            .isCover(listing.getImages().isEmpty())
            .build();
    listing.getImages().add(image);
    recomputeQuality(listing);
    return listings.save(listing);
  }

  @Transactional
  public Listing removeImage(AuthPrincipal user, UUID listingId, UUID imageId) {
    Listing listing = ownedListing(user, listingId);
    ListingImage target =
        listing.getImages().stream()
            .filter(i -> i.getId().equals(imageId))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Photo not found"));
    listing.getImages().remove(target);
    storage.delete(target.getUrl());
    if (target.isCover() && !listing.getImages().isEmpty()) {
      listing.getImages().get(0).setCover(true);
    }
    recomputeQuality(listing);
    return listings.save(listing);
  }

  @Transactional
  public Listing setAmenities(Listing listing, List<String> slugs) {
    listing.setAmenities(new LinkedHashSet<>(amenities.findBySlugIn(slugs)));
    return listings.save(listing);
  }

  Listing ownedListing(AuthPrincipal user, UUID listingId) {
    Listing listing =
        listings
            .findByIdAndDeletedAtIsNull(listingId)
            .orElseThrow(() -> ApiException.notFound("Listing not found"));
    if (!listing.getListerId().equals(user.userId()) && !user.isAdmin()) {
      throw ApiException.forbidden("You can only modify your own listings");
    }
    return listing;
  }

  private void applyOptionalFields(Listing listing, UpsertListingRequest req) {
    if (req.minLeaseMonths() != null) listing.setMinLeaseMonths(req.minLeaseMonths().shortValue());
    if (req.maxOccupants() != null) listing.setMaxOccupants(req.maxOccupants().shortValue());
    if (req.furnishing() != null) listing.setFurnishing(req.furnishing());
    if (req.bathroomAttached() != null) listing.setBathroomAttached(req.bathroomAttached());
    if (req.balcony() != null) listing.setBalcony(req.balcony());
    if (req.preferredGender() != null) listing.setPreferredGender(req.preferredGender());
    if (req.couplesAllowed() != null) listing.setCouplesAllowed(req.couplesAllowed());
    if (req.householdSmoking() != null) listing.setHouseholdSmoking(req.householdSmoking());
    if (req.householdPets() != null) listing.setHouseholdPets(req.householdPets());
    if (req.householdDiet() != null) listing.setHouseholdDiet(req.householdDiet());
    if (req.householdSocial() != null) listing.setHouseholdSocial(req.householdSocial());
    if (req.occupantsDesc() != null) listing.setOccupantsDesc(req.occupantsDesc());
    if (req.amenitySlugs() != null) {
      listing.setAmenities(new LinkedHashSet<>(amenities.findBySlugIn(req.amenitySlugs())));
    }
  }

  private void recomputeQuality(Listing listing) {
    double photos = Math.min(listing.getImages().size() / 4.0, 1);
    double desc = Math.min(listing.getDescription().length() / 400.0, 1);
    double fields = 0;
    Object[] optional = {
      listing.getHouseholdSocial(), listing.getHouseholdSmoking(), listing.getOccupantsDesc(),
      listing.getMaxOccupants(), listing.getBathroomAttached(), listing.getBalcony(),
    };
    for (Object o : optional) {
      if (o != null) fields += 1.0 / optional.length;
    }
    listing.setQualityScore((float) (0.4 * photos + 0.3 * desc + 0.3 * fields));
  }

  static RoomType roomTypeFor(ListingType type) {
    return switch (type) {
      case ENTIRE_APARTMENT -> RoomType.ENTIRE;
      case SHARED_ROOM -> RoomType.SHARED;
      default -> RoomType.PRIVATE;
    };
  }
}
