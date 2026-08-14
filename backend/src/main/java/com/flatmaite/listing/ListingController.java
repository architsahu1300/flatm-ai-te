package com.flatmaite.listing;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.ListingDtos.CardResponse;
import com.flatmaite.listing.ListingDtos.PageResponse;
import com.flatmaite.listing.ListingDtos.StatusRequest;
import com.flatmaite.listing.ListingDtos.UpsertListingRequest;
import com.flatmaite.listing.ListingQueryService.IdPage;
import com.flatmaite.listing.ListingQueryService.Sort;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ListingController {

  private final ListingService listingService;
  private final ListingQueryService queryService;
  private final ListingAssembler assembler;
  private final ListingRepository listings;

  @GetMapping("/listings")
  public ResponseEntity<Map<String, Object>> browse(
      @RequestParam(required = false) String loc,
      @RequestParam(required = false) Integer bmin,
      @RequestParam(required = false) Integer bmax,
      @RequestParam(required = false) String room,
      @RequestParam(required = false) String types,
      @RequestParam(required = false) String furn,
      @RequestParam(required = false) Integer bhkMin,
      @RequestParam(required = false) Integer bhkMax,
      @RequestParam(required = false) String moveInBy,
      @RequestParam(required = false) String gender,
      @RequestParam(required = false) String amen,
      @RequestParam(required = false, defaultValue = "false") boolean verified,
      @RequestParam(required = false, defaultValue = "false") boolean smokeFree,
      @RequestParam(required = false, defaultValue = "false") boolean petFriendly,
      @RequestParam(required = false, defaultValue = "false") boolean veg,
      @RequestParam(required = false) String social,
      @RequestParam(required = false, defaultValue = "newest") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {

    ListingFilters filters =
        ListingFilters.builder()
            .localityIds(parseUuids(loc))
            .budgetMin(bmin)
            .budgetMax(bmax)
            .roomType(parseEnum(RoomType.class, room))
            .listingTypes(parseEnums(ListingType.class, types))
            .furnishings(parseEnums(Furnishing.class, furn))
            .bhkMin(bhkMin)
            .bhkMax(bhkMax)
            .moveInBy(moveInBy == null ? null : LocalDate.parse(moveInBy))
            .genderPref(parseEnum(GenderPreference.class, gender))
            .amenitySlugs(parseList(amen))
            .verifiedOnly(verified)
            .smokeFreeHousehold(smokeFree)
            .petFriendly(petFriendly)
            .vegHousehold(veg)
            .householdSocial(parseEnum(SocialStyle.class, social))
            .build();

    Sort sortBy =
        switch (sort.toLowerCase(Locale.ROOT)) {
          case "price_asc" -> Sort.PRICE_ASC;
          case "price_desc" -> Sort.PRICE_DESC;
          default -> Sort.NEWEST;
        };
    int clampedSize = Math.min(Math.max(size, 1), 50);
    IdPage idPage = queryService.findIds(filters, sortBy, Math.max(page, 0), clampedSize);
    List<CardResponse> cards = assembler.toCards(queryService.hydrate(idPage.ids()));
    return ResponseEntity.ok(
        Map.of("data", new PageResponse<>(cards, page, clampedSize, idPage.total())));
  }

  @GetMapping("/listings/{id}")
  @Transactional
  public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
    Listing listing =
        listings
            .findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> ApiException.notFound("Listing not found"));
    AuthPrincipal viewer = CurrentUser.orNull();
    boolean isOwner =
        viewer != null && (viewer.userId().equals(listing.getListerId()) || viewer.isAdmin());
    if (listing.getStatus() != ListingStatus.ACTIVE && !isOwner) {
      throw ApiException.notFound("Listing not found");
    }
    if (!isOwner) {
      listing.setViewCount(listing.getViewCount() + 1);
      listings.save(listing);
    }
    return ResponseEntity.ok(Map.of("data", assembler.toDetail(listing)));
  }

  @PostMapping("/listings")
  public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody UpsertListingRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Listing listing = listingService.create(user, body);
    return ResponseEntity.ok(Map.of("data", Map.of("id", listing.getId(), "status", listing.getStatus().name())));
  }

  @PatchMapping("/listings/{id}")
  public ResponseEntity<Map<String, Object>> update(
      @PathVariable UUID id, @Valid @RequestBody UpsertListingRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Listing listing = listingService.update(user, id, body);
    return ResponseEntity.ok(Map.of("data", assembler.toDetail(listing)));
  }

  @DeleteMapping("/listings/{id}")
  public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
    listingService.softDelete(CurrentUser.require(), id);
    return ResponseEntity.ok(Map.of("data", Map.of("deleted", true)));
  }

  @PostMapping("/listings/{id}/status")
  public ResponseEntity<Map<String, Object>> changeStatus(
      @PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
    Listing listing = listingService.changeStatus(CurrentUser.require(), id, body.status());
    return ResponseEntity.ok(Map.of("data", Map.of("id", listing.getId(), "status", listing.getStatus().name())));
  }

  @PostMapping("/listings/{id}/images")
  public ResponseEntity<Map<String, Object>> addImage(
      @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
    Listing listing = listingService.addImage(CurrentUser.require(), id, file);
    return ResponseEntity.ok(Map.of("data", assembler.toDetail(listing).images()));
  }

  @DeleteMapping("/listings/{id}/images/{imageId}")
  public ResponseEntity<Map<String, Object>> removeImage(
      @PathVariable UUID id, @PathVariable UUID imageId) {
    Listing listing = listingService.removeImage(CurrentUser.require(), id, imageId);
    return ResponseEntity.ok(Map.of("data", assembler.toDetail(listing).images()));
  }

  @GetMapping("/me/listings")
  public ResponseEntity<Map<String, Object>> myListings() {
    AuthPrincipal user = CurrentUser.require();
    List<Listing> mine = listings.findByListerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(user.userId());
    return ResponseEntity.ok(Map.of("data", assembler.toCards(mine)));
  }

  private static List<UUID> parseUuids(String csv) {
    List<String> parts = parseList(csv);
    return parts == null ? null : parts.stream().map(UUID::fromString).toList();
  }

  private static List<String> parseList(String csv) {
    if (csv == null || csv.isBlank()) {
      return null;
    }
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("invalid_param", "Invalid value '" + value + "'");
    }
  }

  private static <E extends Enum<E>> List<E> parseEnums(Class<E> type, String csv) {
    List<String> parts = parseList(csv);
    return parts == null ? null : parts.stream().map(p -> parseEnum(type, p)).toList();
  }
}
