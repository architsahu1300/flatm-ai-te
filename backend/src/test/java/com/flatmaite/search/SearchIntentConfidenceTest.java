package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Absent confidence must read as 1.0: everything that shipped before this change was enforced as a
 * hard filter, so an old session or a provider that says nothing keeps exactly today's behaviour.
 */
class SearchIntentConfidenceTest {

  @Test
  void absentMapOrKey_readsAsFullyConfident() {
    SearchIntent none = SearchIntent.builder().budgetMax(25000).build();
    assertThat(none.confidence()).isNull();
    assertThat(none.confidenceOf("budgetMax")).isEqualTo(1.0);

    SearchIntent some =
        SearchIntent.builder().budgetMax(25000).confidence(Map.of("roomType", 0.5)).build();
    assertThat(some.confidenceOf("roomType")).isEqualTo(0.5);
    assertThat(some.confidenceOf("budgetMax")).isEqualTo(1.0);
    assertThat(some.confidenceOf("nonsense")).isEqualTo(1.0);
  }

  @Test
  void nullValueInTheMap_readsAsFullyConfident() {
    Map<String, Double> raw = new HashMap<>();
    raw.put("roomType", null);
    assertThat(SearchIntent.builder().confidence(raw).build().confidenceOf("roomType")).isEqualTo(1.0);
  }

  @Test
  void merge_prefersTheNewerValue_andKeepsCarriedSlots() {
    Map<String, Double> prior = new LinkedHashMap<>();
    prior.put("locations", 1.0);
    prior.put("roomType", 0.5);
    Map<String, Double> next = new LinkedHashMap<>();
    next.put("roomType", 1.0);
    next.put("budgetMax", 1.0);

    Map<String, Double> merged = SearchIntent.mergeConfidence(prior, next);

    assertThat(merged).containsEntry("locations", 1.0); // carried from the prior turn
    assertThat(merged).containsEntry("roomType", 1.0); // re-stated this turn, re-graded
    assertThat(merged).containsEntry("budgetMax", 1.0);
  }

  @Test
  void merge_toleratesNulls() {
    assertThat(SearchIntent.mergeConfidence(null, null)).isNull();
    assertThat(SearchIntent.mergeConfidence(Map.of("a", 0.5), null)).containsEntry("a", 0.5);
    assertThat(SearchIntent.mergeConfidence(null, Map.of("b", 0.5))).containsEntry("b", 0.5);
  }

  @Test
  void gatedSlots_areTheSeventeenInSpecOrder() {
    assertThat(SearchIntent.GATED_SLOTS)
        .containsExactly(
            "locations", "excludeLocations", "budgetMin", "budgetMax", "maxDeposit", "roomType",
            "listingTypes", "furnished", "bhk", "moveInDate", "genderPreference", "couplesOk",
            "amenities", "lifestyle", "commuteTo", "commuteTo.maxMinutes", "verifiedOnly");
  }

  @Test
  void confidenceSurvivesAJsonRoundTrip_andOldJsonStillParses() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    SearchIntent intent =
        SearchIntent.builder().budgetMax(25000).confidence(Map.of("roomType", 0.5)).build();
    String json = mapper.writeValueAsString(intent);
    assertThat(json).contains("\"confidence\"");
    assertThat(mapper.readValue(json, SearchIntent.class).confidenceOf("roomType")).isEqualTo(0.5);

    // an intent stored before this change
    SearchIntent old = mapper.readValue("{\"budgetMax\":25000}", SearchIntent.class);
    assertThat(old.confidence()).isNull();
    assertThat(old.confidenceOf("roomType")).isEqualTo(1.0);
  }
}
