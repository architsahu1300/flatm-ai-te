package com.flatmaite.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;

/**
 * The single contract between the LLM, SQL hard filters, the deterministic scorer and the UI's
 * editable chips. Null = "not specified". The original natural-language query is always
 * preserved; excludeLocations are places to avoid and unresolvedLocations are names no resolver
 * layer could place.
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
    List<LocationRef> excludeLocations,
    List<String> unresolvedLocations,
    Boolean verifiedOnly,
    Map<String, Double> confidence,
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

  /** Residual keywords accumulate across a session; bounded so the lexical query and the session JSON cannot grow without limit. */
  public static final int MAX_FREE_TEXT_CHARS = 600;

  /** Commute radius when the user names a workplace without a time — replaces four scattered 45s. */
  public static final int DEFAULT_COMMUTE_MINUTES = 30;

  /**
   * Appends a follow-up's words to the prior residual (blank-safe on both sides), truncating the
   * tail past {@link #MAX_FREE_TEXT_CHARS} so the earliest, richest terms are kept.
   */
  public static String joinFreeText(String prior, String next) {
    String joined;
    if (prior == null || prior.isBlank()) {
      joined = next == null ? null : next.trim();
    } else if (next == null || next.isBlank()) {
      joined = prior.trim();
    } else {
      joined = prior.trim() + " " + next.trim();
    }
    if (joined != null && joined.length() > MAX_FREE_TEXT_CHARS) {
      joined = joined.substring(0, MAX_FREE_TEXT_CHARS).trim();
    }
    return joined;
  }

  public SearchTarget targetOrDefault() {
    return searchTarget == null ? SearchTarget.PROPERTIES : searchTarget;
  }

  public Lifestyle lifestyleOrEmpty() {
    return lifestyle == null ? Lifestyle.builder().build() : lifestyle;
  }

  /**
   * Slots whose enforcement is confidence-gated, in the order used to break ties when the rescue
   * ladder picks which filter to drop first.
   */
  public static final List<String> GATED_SLOTS =
      List.of(
          "locations", "excludeLocations", "budgetMin", "budgetMax", "maxDeposit", "roomType",
          "listingTypes", "furnished", "bhk", "moveInDate", "genderPreference", "couplesOk",
          "amenities", "lifestyle", "commuteTo", "commuteTo.maxMinutes", "verifiedOnly");

  /**
   * How directly the user's own words support this slot. An absent map or key means 1.0: everything
   * that shipped before confidence gating was enforced as a hard filter, and an old session or a
   * silent provider must keep behaving exactly that way.
   */
  public double confidenceOf(String slot) {
    if (confidence == null) {
      return 1.0;
    }
    Double value = confidence.get(slot);
    return value == null ? 1.0 : value;
  }

  /** Refinement merge: the newer turn's grade wins; slots it did not touch keep the prior's. */
  public static Map<String, Double> mergeConfidence(Map<String, Double> prior, Map<String, Double> next) {
    if (prior == null && next == null) {
      return null;
    }
    Map<String, Double> merged = new LinkedHashMap<>();
    if (prior != null) {
      merged.putAll(prior);
    }
    if (next != null) {
      merged.putAll(next);
    }
    return merged;
  }
}
