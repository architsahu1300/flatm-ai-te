package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.IntentLlm;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one referee for new-vs-refine. The AMBIGUOUS + Mode.NEW branch is the only place the model
 * can overrule the detector and discard a user's accumulated intent.
 */
class IntentArbiterTest {

  private static final SearchIntent PRIOR = SearchIntent.builder().budgetMax(20000).build();
  private static final SearchIntent REFINED = SearchIntent.builder().budgetMax(18000).build();
  private static final SearchIntent FRESH = SearchIntent.builder().budgetMax(30000).build();

  /** Records the prior handed to each call and answers from a queue. */
  private static final class RecordingExtractor implements BiFunction<String, SearchIntent, IntentLlm.Extraction> {
    final List<SearchIntent> priors = new ArrayList<>();
    final Deque<IntentLlm.Extraction> answers = new ArrayDeque<>();

    RecordingExtractor answer(SearchIntent intent, IntentLlm.Mode mode) {
      answers.add(new IntentLlm.Extraction(intent, mode));
      return this;
    }

    @Override
    public IntentLlm.Extraction apply(String query, SearchIntent prior) {
      priors.add(prior);
      return answers.pop();
    }
  }

  private NewQueryDetector detector;
  private IntentArbiter arbiter;

  @BeforeEach
  void setUp() {
    detector = mock(NewQueryDetector.class);
    arbiter = new IntentArbiter(detector);
  }

  @Test
  void ambiguous_modelSaysNew_reExtractsWithoutThePrior_andIsFresh() {
    when(detector.decide("flats in powai")).thenReturn(NewQueryDetector.Verdict.AMBIGUOUS);
    RecordingExtractor extract =
        new RecordingExtractor().answer(PRIOR, IntentLlm.Mode.NEW).answer(FRESH, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide("flats in powai", PRIOR, extract);

    assertThat(extract.priors).containsExactly(PRIOR, null);
    assertThat(d.intent()).isEqualTo(FRESH);
    assertThat(d.verdict()).isEqualTo(NewQueryDetector.Verdict.AMBIGUOUS);
    assertThat(d.mode()).isEqualTo(IntentLlm.Mode.NEW);
    assertThat(d.fresh()).isTrue();
    assertThat(d.note()).isEqualTo(IntentArbiter.FRESH_NOTE);
  }

  @Test
  void ambiguous_modelSaysRefine_extractsOnceWithThePrior_noNote() {
    when(detector.decide("cheaper please")).thenReturn(NewQueryDetector.Verdict.AMBIGUOUS);
    RecordingExtractor extract = new RecordingExtractor().answer(REFINED, IntentLlm.Mode.REFINE);

    IntentArbiter.Decision d = arbiter.decide("cheaper please", PRIOR, extract);

    assertThat(extract.priors).containsExactly(PRIOR);
    assertThat(d.intent()).isEqualTo(REFINED);
    assertThat(d.fresh()).isFalse();
    assertThat(d.note()).isNull();
  }

  @Test
  void detectorSaysNew_extractsOnceWithoutThePrior_andIsFresh() {
    String q = "forget that, single sharing room in goregaon 20k";
    when(detector.decide(q)).thenReturn(NewQueryDetector.Verdict.NEW);
    RecordingExtractor extract = new RecordingExtractor().answer(FRESH, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide(q, PRIOR, extract);

    assertThat(extract.priors).containsExactly((SearchIntent) null);
    assertThat(d.verdict()).isEqualTo(NewQueryDetector.Verdict.NEW);
    assertThat(d.fresh()).isTrue();
  }

  @Test
  void detectorSaysRefine_extractsOnceWithThePrior() {
    when(detector.decide("make it 18k")).thenReturn(NewQueryDetector.Verdict.REFINE);
    RecordingExtractor extract = new RecordingExtractor().answer(REFINED, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide("make it 18k", PRIOR, extract);

    assertThat(extract.priors).containsExactly(PRIOR);
    assertThat(d.intent()).isEqualTo(REFINED);
    assertThat(d.fresh()).isFalse();
  }

  @Test
  void noPrior_detectorNeverConsulted_singleExtraction_notFresh() {
    RecordingExtractor extract = new RecordingExtractor().answer(FRESH, IntentLlm.Mode.NONE);

    IntentArbiter.Decision d = arbiter.decide("single sharing room in goregaon 20k", null, extract);

    verifyNoInteractions(detector);
    assertThat(extract.priors).containsExactly((SearchIntent) null);
    assertThat(d.verdict()).isEqualTo(NewQueryDetector.Verdict.NEW);
    assertThat(d.fresh()).isFalse();
    assertThat(d.note()).isNull();
  }
}
