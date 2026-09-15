package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flatmaite.eval.IntentComparator.SlotResult;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalReportTest {

  private static GoldenCase golden(String id, boolean mustPass, NewQueryDetector.Verdict verdict, String... tags) {
    return new GoldenCase(id, List.of(tags), "q " + id, null, mustPass, verdict, true, SearchIntent.builder().build());
  }

  private static SlotResult ok(String slot) {
    return new SlotResult(slot, true, "x", "x");
  }

  private static SlotResult bad(String slot) {
    return new SlotResult(slot, false, "x", "y");
  }

  /** Three gated cases (2 pass, 1 fail on budgetMax), one known gap, one verdict-only case. */
  private static EvalReport sample() {
    return new EvalReport(List.of(
        new CaseResult(golden("a", true, null, "basics"), List.of(ok("locations"), ok("budgetMax")), NewQueryDetector.Verdict.NEW, null, 1),
        new CaseResult(golden("b", false, null, "basics", "budget"), List.of(ok("locations"), bad("budgetMax")), NewQueryDetector.Verdict.NEW, null, 1),
        new CaseResult(golden("c", false, NewQueryDetector.Verdict.REFINE, "refinement"), List.of(ok("locations")), NewQueryDetector.Verdict.REFINE, null, 1),
        new CaseResult(golden("gap", false, null, "budget", GoldenCase.KNOWN_GAP), List.of(bad("budgetMax")), NewQueryDetector.Verdict.NEW, null, 1),
        new CaseResult(golden("v", false, NewQueryDetector.Verdict.NEW, "arbiter"), List.of(), NewQueryDetector.Verdict.REFINE, null, 1)));
  }

  @Test
  void passRates_excludeKnownGaps() {
    EvalReport r = sample();
    assertThat(r.gated()).extracting(cr -> cr.golden().id()).containsExactly("a", "b", "c", "v");
    assertThat(r.casePassRate()).isEqualTo(2.0 / 4); // a, c pass; b (slot) and v (verdict) fail
    assertThat(r.knownGaps()).extracting(cr -> cr.golden().id()).containsExactly("gap");
  }

  @Test
  void slotAccuracy_countsOnlySlotsPresentOnEitherSide() {
    Map<String, Double> acc = sample().slotAccuracy();
    assertThat(acc.get("locations")).isEqualTo(1.0);
    assertThat(acc.get("budgetMax")).isEqualTo(0.5); // a ok, b bad (gap excluded)
    assertThat(acc).doesNotContainKey("roomType"); // never present
  }

  @Test
  void tagAndVerdictRates() {
    EvalReport r = sample();
    assertThat(r.tagPassRate().get("basics")).isEqualTo(0.5);
    assertThat(r.tagPassRate().get("budget")).isEqualTo(0.0);
    assertThat(r.verdictAccuracy()).isEqualTo(0.5); // c right, v wrong
  }

  @Test
  void failuresAndMustPass() {
    EvalReport r = sample();
    assertThat(r.failures()).extracting(cr -> cr.golden().id()).containsExactly("b", "v");
    assertThat(r.mustPassFailures()).isEmpty();
    assertThat(r.results().get(1).firstMismatch()).isEqualTo("budgetMax: expected x, got y");
    assertThat(r.results().get(4).firstMismatch()).isEqualTo("verdict: expected NEW, got REFINE");
  }

  @Test
  void thresholds_reportEveryViolation() {
    List<String> v = EvalThresholds.violations(sample());
    assertThat(v).anyMatch(s -> s.contains("case pass rate"));
    assertThat(v).anyMatch(s -> s.contains("budgetMax"));
    assertThat(v).noneMatch(s -> s.contains("locations"));
  }

  @Test
  void tableAndJson() {
    EvalReport r = sample();
    String table = r.renderTable();
    assertThat(table.lines()).hasSize(6); // header + 5 cases
    assertThat(table).contains("PASS").contains("FAIL").contains("GAP").contains("budgetMax: expected x, got y");
    ObjectNode json = r.toJson(new ObjectMapper(), Map.of("provider", "mock"));
    assertThat(json.path("meta").path("provider").asText()).isEqualTo("mock");
    assertThat(json.path("casePassRate").asDouble()).isEqualTo(0.5);
    assertThat(json.path("cases")).hasSize(5);
    assertThat(json.path("cases").get(1).path("firstMismatch").asText()).contains("budgetMax");
  }
}
