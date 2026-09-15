package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.Furnishing;
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

  @Test
  void gradingFailure_clearsEveryGrade_soNothingStaysSoft() {
    LocalityResolver exploding = org.mockito.Mockito.mock(LocalityResolver.class);
    org.mockito.Mockito.when(exploding.scan(org.mockito.ArgumentMatchers.anyString()))
        .thenThrow(new IllegalStateException("resolver down"));
    // the model already wrote its own self-rating into the intent — it must not survive
    SearchIntent claimed =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .confidence(Map.of("roomType", 0.2))
            .build();

    SearchIntent graded =
        SearchPipeline.withConfidence(claimed, "2bhk in powai", null, exploding);

    assertThat(graded.confidence()).isNull();
    assertThat(graded.confidenceOf("roomType")).isEqualTo(1.0);
    assertThat(graded.roomType()).isEqualTo(RoomType.ENTIRE); // the intent itself is untouched
  }

  @Test
  void aNegativeSelfRatingFloorsAtZero_andANullValueIsIgnored() {
    Map<String, Double> selfRating = new java.util.HashMap<>();
    selfRating.put("roomType", -1.0);
    selfRating.put("budgetMax", null);

    Map<String, Double> merged =
        SearchPipeline.combineConfidence(Map.of("roomType", 1.0, "budgetMax", 1.0), selfRating);

    assertThat(merged).containsEntry("roomType", 0.0);
    assertThat(merged).containsEntry("budgetMax", 1.0);
  }

  @Test
  void aCarriedConstraintKeepsTheGradeItEarned() {
    SearchIntent prior =
        SearchIntent.builder().budgetMax(40000).confidence(Map.of("budgetMax", 1.0)).build();
    SearchIntent next = SearchIntent.builder().budgetMax(40000).furnished(Furnishing.FULLY_FURNISHED).build();

    // this turn's words ("make it fully furnished") never mention the budget
    Map<String, Double> merged =
        SearchPipeline.carryConfidence(prior, next, Map.of("budgetMax", 0.5, "furnished", 1.0));

    assertThat(merged).containsEntry("budgetMax", 1.0);
    assertThat(merged).containsEntry("furnished", 1.0);
  }

  @Test
  void aChangedConstraintIsGradedByThisTurnsWordsAlone() {
    SearchIntent prior =
        SearchIntent.builder().budgetMax(40000).confidence(Map.of("budgetMax", 1.0)).build();
    SearchIntent next = SearchIntent.builder().budgetMax(30000).build();

    assertThat(SearchPipeline.carryConfidence(prior, next, Map.of("budgetMax", 0.5)))
        .containsEntry("budgetMax", 0.5);
  }

  @Test
  void restatingAConstraintMoreClearlyRaisesItsGrade() {
    SearchIntent prior =
        SearchIntent.builder().roomType(RoomType.PRIVATE).confidence(Map.of("roomType", 0.75)).build();
    SearchIntent next = SearchIntent.builder().roomType(RoomType.PRIVATE).build();

    assertThat(SearchPipeline.carryConfidence(prior, next, Map.of("roomType", 1.0)))
        .containsEntry("roomType", 1.0);
  }

  @Test
  void withNoPriorOrNoPriorGrades_thisTurnsGradesStand() {
    Map<String, Double> graded = Map.of("roomType", 0.5);
    assertThat(SearchPipeline.carryConfidence(null, SearchIntent.builder().build(), graded)).isEqualTo(graded);
    assertThat(
            SearchPipeline.carryConfidence(
                SearchIntent.builder().build(), SearchIntent.builder().build(), graded))
        .isEqualTo(graded);
  }

  @Test
  void aHeuristicRefinementKeepsThePriorGrades_becauseTheUserCommandedIt() {
    SearchIntent prior =
        SearchIntent.builder()
            .budgetMax(40000)
            .locations(java.util.List.of(new SearchIntent.LocationRef("Powai", null)))
            .confidence(Map.of("budgetMax", 1.0, "locations", 1.0))
            .build();

    // the pipeline's heuristic branch hands on the prior's map untouched, whatever the new number is
    SearchIntent heuristic = prior.toBuilder().budgetMax(36000).build();
    SearchIntent carried =
        heuristic.toBuilder().confidence(prior.confidence()).build();

    assertThat(carried.confidenceOf("budgetMax")).isEqualTo(1.0);
    assertThat(carried.confidenceOf("locations")).isEqualTo(1.0);
  }
}
