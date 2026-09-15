package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.IntentLlm;
import com.flatmaite.ai.MockLlms;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.IntentLocalities;
import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.LocalityResolver;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.RefinementHeuristics;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.seed.SeedLocalities;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The offline gate: the keyword parser (the mock LLM) and the arbiter against the golden set, over
 * the seed gazetteer, with no Spring and no database. Mirrors SearchPipeline.extractIntent's
 * mock path: heuristics first, then the parser, then locality resolution.
 */
class IntentGoldenTest {

  private static LocalityResolver resolver;
  private static IntentEvaluator.Extractor extractor;
  private static Function<LocationRef, String> nameOf;

  @BeforeAll
  static void wire() {
    LocalityRepository repo = mock(LocalityRepository.class);
    when(repo.findAll()).thenReturn(SeedLocalities.entities());
    resolver = new LocalityResolver(repo);
    resolver.reload();
    KeywordIntentParser parser = new KeywordIntentParser(resolver);
    IntentLlm llm = new MockLlms.MockIntentLlm(parser);
    IntentArbiter arbiter = new IntentArbiter(new NewQueryDetector(resolver));
    nameOf = ref -> ref.localityId() != null ? resolver.nameOf(ref.localityId()) : ref.name();
    extractor =
        (query, prior) -> {
          SearchIntent p = prior == null ? null : IntentLocalities.resolve(prior, resolver);
          return arbiter.decide(
              query,
              p,
              (q, pp) -> {
                SearchIntent heuristic = RefinementHeuristics.apply(pp, q);
                SearchIntent raw = heuristic != null ? heuristic : llm.extract(q, pp);
                return new IntentLlm.Extraction(IntentLocalities.resolve(raw, resolver), IntentLlm.Mode.NONE);
              });
        };
  }

  @Test
  void keywordParser_meetsTheBar() {
    EvalReport report = IntentEvaluator.run(GoldenSet.load(), extractor, nameOf, r -> {});
    List<String> violations = EvalThresholds.violations(report);
    assertThat(violations)
        .withFailMessage(
            "Intent eval thresholds violated:\n  %s\n\n%s\n\ncase pass rate %.3f, slot accuracy %s",
            String.join("\n  ", violations), report.renderTable(), report.casePassRate(), report.slotAccuracy())
        .isEmpty();
  }

  @Test
  void knownGaps_areListed_notHidden() {
    EvalReport report = IntentEvaluator.run(GoldenSet.load(), extractor, nameOf, r -> {});
    System.out.println("known gaps (" + report.knownGaps().size() + "):");
    report.knownGaps().forEach(r -> System.out.println("  " + r.golden().id() + " — " + r.firstMismatch()));
    assertThat(report.knownGaps()).allMatch(r -> !r.golden().mustPass());
  }
}
