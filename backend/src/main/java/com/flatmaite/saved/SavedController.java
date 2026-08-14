package com.flatmaite.saved;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingAssembler;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.search.AiUsageService;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchPipeline;
import com.flatmaite.search.SearchSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SavedController {

  private final SavedListingRepository savedListings;
  private final SavedSearchRepository savedSearches;
  private final ListingQueryService listingQueryService;
  private final ListingAssembler assembler;
  private final SearchPipeline pipeline;
  private final SearchSessionService sessions;
  private final AiUsageService usage;
  private final ObjectMapper objectMapper;

  // ---------- saved listings ----------

  public record SaveListingRequest(@NotNull UUID listingId, @Size(max = 280) String note) {}

  @GetMapping("/saved-listings")
  public ResponseEntity<Map<String, Object>> savedListings() {
    AuthPrincipal user = CurrentUser.require();
    List<SavedListing> saved = savedListings.findByKeyUserIdOrderByCreatedAtDesc(user.userId());
    List<UUID> ids = saved.stream().map(s -> s.getKey().getListingId()).toList();
    List<Listing> hydrated = listingQueryService.hydrate(ids);
    return ResponseEntity.ok(Map.of("data", assembler.toCards(hydrated)));
  }

  @PostMapping("/saved-listings")
  public ResponseEntity<Map<String, Object>> save(@Valid @RequestBody SaveListingRequest body) {
    AuthPrincipal user = CurrentUser.require();
    SavedListing.Key key = new SavedListing.Key(user.userId(), body.listingId());
    if (savedListings.findById(key).isEmpty()) {
      savedListings.save(SavedListing.builder().key(key).note(body.note()).build());
    }
    return ResponseEntity.ok(Map.of("data", Map.of("saved", true)));
  }

  @DeleteMapping("/saved-listings/{listingId}")
  public ResponseEntity<Map<String, Object>> unsave(@PathVariable UUID listingId) {
    AuthPrincipal user = CurrentUser.require();
    savedListings.deleteById(new SavedListing.Key(user.userId(), listingId));
    return ResponseEntity.ok(Map.of("data", Map.of("saved", false)));
  }

  @GetMapping("/saved-listings/ids")
  public ResponseEntity<Map<String, Object>> savedIds() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of(
            "data",
            savedListings.findByKeyUserIdOrderByCreatedAtDesc(user.userId()).stream()
                .map(s -> s.getKey().getListingId())
                .toList()));
  }

  // ---------- saved searches ----------

  public record SaveSearchRequest(
      @NotBlank @Size(max = 80) String name, @NotNull SearchIntent intent, Boolean alertsEnabled) {}

  public record UpdateSearchRequest(@Size(min = 1, max = 80) String name, Boolean alertsEnabled) {}

  @GetMapping("/saved-searches")
  public ResponseEntity<Map<String, Object>> listSearches() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", savedSearches.findByUserIdOrderByUpdatedAtDesc(user.userId())));
  }

  @SneakyThrows
  @PostMapping("/saved-searches")
  public ResponseEntity<Map<String, Object>> createSearch(@Valid @RequestBody SaveSearchRequest body) {
    AuthPrincipal user = CurrentUser.require();
    SavedSearch saved =
        savedSearches.save(
            SavedSearch.builder()
                .userId(user.userId())
                .name(body.name())
                .intent(objectMapper.writeValueAsString(body.intent()))
                .alertsEnabled(Boolean.TRUE.equals(body.alertsEnabled()))
                .build());
    return ResponseEntity.ok(Map.of("data", saved));
  }

  @PatchMapping("/saved-searches/{id}")
  public ResponseEntity<Map<String, Object>> updateSearch(
      @PathVariable UUID id, @Valid @RequestBody UpdateSearchRequest body) {
    SavedSearch search = owned(id);
    if (body.name() != null) {
      search.setName(body.name());
    }
    if (body.alertsEnabled() != null) {
      search.setAlertsEnabled(body.alertsEnabled());
    }
    return ResponseEntity.ok(Map.of("data", savedSearches.save(search)));
  }

  @DeleteMapping("/saved-searches/{id}")
  public ResponseEntity<Map<String, Object>> deleteSearch(@PathVariable UUID id) {
    savedSearches.delete(owned(id));
    return ResponseEntity.ok(Map.of("data", Map.of("deleted", true)));
  }

  /** Replays the stored intent through the pipeline — same engine, fresh results. */
  @SneakyThrows
  @PostMapping("/saved-searches/{id}/run")
  public ResponseEntity<Map<String, Object>> runSearch(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    SavedSearch search = owned(id);
    usage.checkQuota(user.userId(), null);
    SearchIntent intent = objectMapper.readValue(search.getIntent(), SearchIntent.class);
    var session = sessions.start(user.userId(), null, intent, "(saved search: " + search.getName() + ")");
    var result = pipeline.search(intent, user.userId(), null, session.getId());
    search.setLastRunAt(Instant.now());
    search.setLastResultCount(result.homes().size() + result.flatmates().size());
    savedSearches.save(search);
    return ResponseEntity.ok(Map.of("data", result));
  }

  private SavedSearch owned(UUID id) {
    AuthPrincipal user = CurrentUser.require();
    SavedSearch search =
        savedSearches.findById(id).orElseThrow(() -> ApiException.notFound("Saved search not found"));
    if (!search.getUserId().equals(user.userId())) {
      throw ApiException.forbidden("Not your saved search");
    }
    return search;
  }
}
