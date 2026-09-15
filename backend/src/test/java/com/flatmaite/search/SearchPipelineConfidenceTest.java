package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.RoomType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The model's opinion of itself never raises a grade — only the user's words can. */
class SearchPipelineConfidenceTest {

  @Test
  void selfRatingAndGrounding_combineByMinimum() {
    Map<String, Double> grounding = Map.of("roomType", 0.5, "budgetMax", 1.0, "bhk", 1.0);
    Map<String, Double> selfRating = Map.of("roomType", 0.9, "budgetMax", 0.4);

    Map<String, Double> merged = SearchPipeline.combineConfidence(grounding, selfRating);

    assertThat(merged).containsEntry("roomType", 0.5); // grounding is lower
    assertThat(merged).containsEntry("budgetMax", 0.4); // the model doubts its own read
    assertThat(merged).containsEntry("bhk", 1.0); // no self-rating offered
  }

  @Test
  void aSelfRatingForASlotWithNoGrounding_isIgnored() {
    Map<String, Double> merged =
        SearchPipeline.combineConfidence(Map.of("roomType", 1.0), Map.of("furnished", 0.2));
    assertThat(merged).containsOnlyKeys("roomType");
  }

  @Test
  void selfRatingsAreClampedAndNullsTolerated() {
    Map<String, Double> merged =
        SearchPipeline.combineConfidence(
            Map.of("roomType", 1.0, "bhk", 1.0), java.util.Collections.singletonMap("roomType", 5.0));
    assertThat(merged).containsEntry("roomType", 1.0).containsEntry("bhk", 1.0);
    assertThat(SearchPipeline.combineConfidence(Map.of("roomType", 1.0), null))
        .containsEntry("roomType", 1.0);
    assertThat(SearchPipeline.combineConfidence(null, Map.of("roomType", 0.2))).isNull();
  }

  @Test
  void aRefinementKeepsThePriorsGradeForSlotsItDidNotTouch() {
    SearchIntent prior =
        SearchIntent.builder()
            .roomType(RoomType.PRIVATE)
            .budgetMax(40000)
            .confidence(Map.of("roomType", 0.5, "budgetMax", 1.0))
            .build();
    SearchIntent next =
        SearchIntent.builder()
            .roomType(RoomType.PRIVATE)
            .budgetMax(30000)
            .confidence(Map.of("budgetMax", 1.0))
            .build();

    Map<String, Double> merged = SearchIntent.mergeConfidence(prior.confidence(), next.confidence());

    assertThat(merged).containsEntry("roomType", 0.5).containsEntry("budgetMax", 1.0);
  }
}
