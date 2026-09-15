package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.common.domain.SearchTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentEvaluatorTest {

  // Case "one" expects roomType ENTIRE too — the extractor below never produces it for "first",
  // so case "one" fails on that slot. That failure is the point: it makes the prior case "two"
  // inherits observable — priors.get(1) is case "one"'s EXPECTED intent (roomType ENTIRE), not
  // what the actual extraction returned (no roomType at all).
  private static final String JSON = """
      {"version":1,"cases":[
        {"id":"one","tags":["t"],"query":"first","prior":null,"mustPass":true,"expectVerdict":null,
         "expect":{"searchTarget":"PROPERTIES","budgetMax":40000,"roomType":"ENTIRE"}},
        {"id":"two","tags":["t"],"query":"second","prior":{"case":"one"},"mustPass":false,"expectVerdict":"REFINE",
         "expect":{"searchTarget":"PROPERTIES","budgetMax":30000}},
        {"id":"boom","tags":["t"],"query":"explode","prior":null,"mustPass":false,"expectVerdict":null,"expect":{}}]}
      """;

  @Test
  void runsEveryCase_handsThePriorsExpectedIntentToFollowUps_andRecordsErrors() {
    List<SearchIntent> priors = new ArrayList<>();
    IntentEvaluator.Extractor extractor = (query, prior) -> {
      priors.add(prior);
      if (query.equals("explode")) {
        throw new IllegalStateException("provider down");
      }
      SearchIntent intent = SearchIntent.builder().searchTarget(SearchTarget.PROPERTIES)
          .budgetMax(query.equals("first") ? 40000 : 30000).build();
      return new IntentArbiter.Decision(intent, prior == null ? NewQueryDetector.Verdict.NEW : NewQueryDetector.Verdict.REFINE, IntentLlm.Mode.NONE, false);
    };
    List<String> seen = new ArrayList<>();

    EvalReport report = IntentEvaluator.run(GoldenSet.parse(JSON), extractor, ref -> ref.name(), r -> seen.add(r.golden().id()));

    assertThat(seen).containsExactly("one", "two", "boom");
    assertThat(priors.get(0)).isNull();
    assertThat(priors.get(1).roomType()).isEqualTo(RoomType.ENTIRE);
    assertThat(priors.get(1).budgetMax()).isEqualTo(40000);
    assertThat(report.results().get(0).passed()).isFalse();
    assertThat(report.results().get(0).firstMismatch()).contains("roomType");
    assertThat(report.results().get(1).passed()).isTrue();
    assertThat(report.results().get(2).passed()).isFalse();
    assertThat(report.results().get(2).error()).contains("IllegalStateException").contains("provider down");
    assertThat(report.casePassRate()).isEqualTo(1.0 / 3);
  }
}
