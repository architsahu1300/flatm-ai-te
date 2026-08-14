package com.flatmaite.listing;

import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.domain.VerificationType;
import com.flatmaite.listing.ListingDtos.CardResponse;
import com.flatmaite.listing.ListingDtos.DetailResponse;
import com.flatmaite.listing.ListingDtos.ImageResponse;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import com.flatmaite.verification.Verification;
import com.flatmaite.verification.VerificationRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Batch-assembles listing DTOs: locality names, verification badges, cover images. */
@Component
@RequiredArgsConstructor
public class ListingAssembler {

  private final PropertyRepository properties;
  private final LocalityRepository localities;
  private final VerificationRepository verifications;
  private final UserRepository users;

  public List<CardResponse> toCards(List<Listing> page) {
    Map<UUID, Property> propertyById = propertiesFor(page);
    Map<UUID, String> localityNames = localityNames();
    Set<UUID> verifiedUsers = verifiedUserIds(page);
    return page.stream().map(l -> toCard(l, propertyById, localityNames, verifiedUsers)).toList();
  }

  public DetailResponse toDetail(Listing l) {
    Map<UUID, Property> propertyById = propertiesFor(List.of(l));
    Property property = propertyById.get(l.getPropertyId());
    CardResponse card =
        toCard(l, propertyById, localityNames(), verifiedUserIds(List.of(l)));
    User lister = users.findById(l.getListerId()).orElse(null);

    Double lat = null;
    Double lng = null;
    if (property != null && property.getLat() != null && property.getLng() != null) {
      // Deterministic ~350m jitter — public maps never show the exact point
      long h = l.getId().getMostSignificantBits();
      lat = property.getLat() + ((h % 1000) / 1000.0 - 0.5) * 0.006;
      lng = property.getLng() + (((h >> 10) % 1000) / 1000.0 - 0.5) * 0.006;
    }

    return new DetailResponse(
        card,
        l.getDescription(),
        (int) l.getMinLeaseMonths(),
        l.getMaxOccupants() == null ? null : l.getMaxOccupants().intValue(),
        l.getBathroomAttached(),
        l.getBalcony(),
        l.isCouplesAllowed(),
        l.getImages().stream()
            .map(i -> new ImageResponse(i.getId(), i.getUrl(), i.isCover(), i.getSortOrder()))
            .toList(),
        l.getAmenities().stream().map(Amenity::getLabel).sorted().toList(),
        lat,
        lng,
        l.getListerId(),
        lister == null ? null : lister.getName(),
        lister == null ? null : lister.getImage(),
        l.getViewCount());
  }

  private CardResponse toCard(
      Listing l,
      Map<UUID, Property> propertyById,
      Map<UUID, String> localityNames,
      Set<UUID> verifiedUsers) {
    Property property = l.getPropertyId() == null ? null : propertyById.get(l.getPropertyId());
    String cover =
        l.getImages().stream()
            .filter(ListingImage::isCover)
            .findFirst()
            .or(() -> l.getImages().stream().findFirst())
            .map(ListingImage::getUrl)
            .orElse(null);
    return new CardResponse(
        l.getId(),
        l.getType(),
        l.getStatus(),
        l.getTitle(),
        l.getRentMonthly(),
        l.getDeposit(),
        l.getMaintenanceMonthly(),
        property == null ? null : localityNames.get(property.getLocalityId()),
        property == null ? null : property.getLocalityId(),
        property == null ? null : (int) property.getBhk(),
        l.getRoomType(),
        l.getFurnishing(),
        l.getAvailableFrom(),
        cover,
        l.getAmenities().stream().map(Amenity::getSlug).sorted().toList(),
        l.getPreferredGender(),
        l.getHouseholdSocial(),
        l.getHouseholdSmoking(),
        l.getHouseholdPets(),
        l.getHouseholdDiet(),
        l.getOccupantsDesc(),
        verifiedUsers.contains(l.getListerId()),
        property != null && property.isVerified(),
        l.isBoosted(),
        l.getUpdatedAt() == null ? null : l.getUpdatedAt().toString());
  }

  private Map<UUID, Property> propertiesFor(List<Listing> page) {
    List<UUID> ids = page.stream().map(Listing::getPropertyId).filter(Objects::nonNull).toList();
    Map<UUID, Property> map = new HashMap<>();
    properties.findAllById(ids).forEach(p -> map.put(p.getId(), p));
    return map;
  }

  private Map<UUID, String> localityNames() {
    Map<UUID, String> map = new HashMap<>();
    localities.findAll().forEach(loc -> map.put(loc.getId(), loc.getName()));
    return map;
  }

  private Set<UUID> verifiedUserIds(List<Listing> page) {
    List<UUID> listerIds = page.stream().map(Listing::getListerId).distinct().toList();
    Set<UUID> verified = new HashSet<>();
    for (Verification v : verifications.findByUserIdIn(listerIds)) {
      if (v.getStatus() == VerificationStatus.VERIFIED
          && (v.getType() == VerificationType.GOV_ID || v.getType() == VerificationType.SELFIE)) {
        verified.add(v.getUserId());
      }
    }
    return verified;
  }
}
