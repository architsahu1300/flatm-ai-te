package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoldenSetTest {

  private static String sample() throws IOException {
    try (var in = GoldenSetTest.class.getResourceAsStream("/eval/sample-golden.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void parsesCases_expectedIntent_andPriors() throws IOException {
    GoldenSet set = GoldenSet.parse(sample());

    assertThat(set.version()).isEqualTo(1);
    assertThat(set.cases()).extracting(GoldenCase::id)
        .containsExactly("sample-first", "sample-followup", "sample-verdict-only");
    GoldenCase first = set.cases().get(0);
    assertThat(first.expected().locations()).extracting(SearchIntent.LocationRef::name).containsExactly("Powai");
    assertThat(first.expected().bhk().min()).isEqualTo(2);
    assertThat(first.expected().budgetMax()).isEqualTo(40000);
    assertThat(first.expected().roomType()).isNull(); // omitted = null
    assertThat(first.scoreIntent()).isTrue();

    GoldenCase followup = set.cases().get(1);
    assertThat(followup.expectVerdict()).isEqualTo(NewQueryDetector.Verdict.REFINE);
    assertThat(set.priorOf(followup)).isEqualTo(first.expected());
    assertThat(set.priorOf(first)).isNull();

    GoldenCase verdictOnly = set.cases().get(2);
    assertThat(verdictOnly.scoreIntent()).isFalse();
    assertThat(verdictOnly.knownGap()).isTrue();
  }

  @Test
  void duplicateId_isRejected() {
    String json = """
        {"version":1,"cases":[
          {"id":"a","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}},
          {"id":"a","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("a");
  }

  @Test
  void priorMustNameAnEarlierCase() {
    String json = """
        {"version":1,"cases":[
          {"id":"b","tags":[],"query":"q","prior":{"case":"later"},"mustPass":false,"expectVerdict":null,"expect":{}},
          {"id":"later","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("b");
  }

  @Test
  void unknownExpectKey_isRejected_withTheCaseId() {
    String json = """
        {"version":1,"cases":[
          {"id":"typo","tags":[],"query":"q","prior":null,"mustPass":false,"expectVerdict":null,"expect":{"budgetmax":1}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("typo")
        .hasMessageContaining("budgetmax");
  }

  @Test
  void knownGap_mayNotBeMustPass() {
    String json = """
        {"version":1,"cases":[
          {"id":"gap","tags":["known-gap"],"query":"q","prior":null,"mustPass":true,"expectVerdict":null,"expect":{}}]}
        """;
    assertThatThrownBy(() -> GoldenSet.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("gap");
  }

  @Test
  void filter_keepsPriorsResolvable() throws IOException {
    GoldenSet filtered = GoldenSet.parse(sample()).filter(Set.of("refinement"), 0);

    assertThat(filtered.cases()).extracting(GoldenCase::id).containsExactly("sample-followup");
    assertThat(filtered.priorOf(filtered.cases().get(0)).budgetMax()).isEqualTo(40000);
    assertThat(GoldenSet.parse(sample()).filter(Set.of(), 2).cases()).hasSize(2);
  }
}
