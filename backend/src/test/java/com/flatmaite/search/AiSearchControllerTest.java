package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.AiSearchSession;
import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.ratelimit.RateLimiter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The AMBIGUOUS + Mode.NEW branch is the one place the model can overrule the detector and
 * discard a user's accumulated intent — the highest-blast-radius path in the branch, and (before
 * this test) the one with no coverage.
 */
class AiSearchControllerTest {

  private SearchPipeline pipeline;
  private SearchSessionService sessions;
  private AiUsageService usage;
  private RateLimiter rateLimiter;
  private NewQueryDetector detector;
  private AiSearchController controller;

  @BeforeEach
  void setUp() {
    pipeline = mock(SearchPipeline.class);
    sessions = mock(SearchSessionService.class);
    usage = mock(AiUsageService.class);
    rateLimiter = mock(RateLimiter.class);
    detector = mock(NewQueryDetector.class);
    controller = new AiSearchController(pipeline, sessions, usage, rateLimiter, detector);

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(pipeline.search(any(), any(), any(), any(), any())).thenReturn(dummyResponse());
  }

  private static SearchDtos.AiSearchResponse dummyResponse() {
    return new SearchDtos.AiSearchResponse(null, null, "mock", List.of(), List.of(), List.of(), null);
  }

  private AiSearchSession mockSession(UUID sessionId) {
    AiSearchSession session = mock(AiSearchSession.class);
    when(session.getId()).thenReturn(sessionId);
    return session;
  }

  @Test
  void ambiguous_modelOverrulesTheDetector_reExtractsFresh_andNotesIt() {
    String query = "flats in powai";
    UUID sessionId = UUID.randomUUID();
    SearchIntent prior = SearchIntent.builder().budgetMax(20000).build();
    SearchIntent freshIntent = SearchIntent.builder().budgetMax(30000).build();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    when(sessions.intentOf(session)).thenReturn(prior);
    when(detector.decide(query)).thenReturn(NewQueryDetector.Verdict.AMBIGUOUS);

    ArgumentCaptor<SearchIntent> priorCaptor = ArgumentCaptor.forClass(SearchIntent.class);
    when(pipeline.extractIntent(eq(query), priorCaptor.capture(), any(), any()))
        .thenReturn(new IntentLlm.Extraction(prior, IntentLlm.Mode.NEW))
        .thenReturn(new IntentLlm.Extraction(freshIntent, IntentLlm.Mode.NONE));

    controller.search(new SearchDtos.AiSearchRequest(query, sessionId), new MockHttpServletRequest(), new MockHttpServletResponse());

    // first call carried the prior; the second (re-extraction) carried none
    assertThat(priorCaptor.getAllValues()).containsExactly(prior, null);

    ArgumentCaptor<SearchIntent> searchIntentCaptor = ArgumentCaptor.forClass(SearchIntent.class);
    ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
    verify(pipeline).search(searchIntentCaptor.capture(), any(), any(), any(), noteCaptor.capture());
    assertThat(searchIntentCaptor.getValue()).isEqualTo(freshIntent);
    assertThat(noteCaptor.getValue()).isEqualTo(AiSearchController.FRESH_NOTE);
  }

  @Test
  void ambiguous_modelAgreesItsARefinement_extractsOnce_noNote() {
    String query = "cheaper please";
    UUID sessionId = UUID.randomUUID();
    SearchIntent prior = SearchIntent.builder().budgetMax(20000).build();
    SearchIntent refined = SearchIntent.builder().budgetMax(18000).build();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    when(sessions.intentOf(session)).thenReturn(prior);
    when(detector.decide(query)).thenReturn(NewQueryDetector.Verdict.AMBIGUOUS);
    when(pipeline.extractIntent(eq(query), eq(prior), any(), any()))
        .thenReturn(new IntentLlm.Extraction(refined, IntentLlm.Mode.REFINE));

    controller.search(new SearchDtos.AiSearchRequest(query, sessionId), new MockHttpServletRequest(), new MockHttpServletResponse());

    verify(pipeline, times(1)).extractIntent(any(), any(), any(), any());
    verify(pipeline).extractIntent(eq(query), eq(prior), isNull(), any());

    ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
    verify(pipeline).search(any(), any(), any(), any(), noteCaptor.capture());
    assertThat(noteCaptor.getValue()).isNull();
  }

  @Test
  void detectorSaysNew_extractsOnceWithNoPrior_andNotesIt() {
    String query = "forget that, single sharing room in goregaon 20k";
    UUID sessionId = UUID.randomUUID();
    SearchIntent prior = SearchIntent.builder().budgetMax(20000).build();
    SearchIntent fresh = SearchIntent.builder().budgetMax(20000).build();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    when(sessions.intentOf(session)).thenReturn(prior);
    when(detector.decide(query)).thenReturn(NewQueryDetector.Verdict.NEW);
    when(pipeline.extractIntent(eq(query), isNull(), any(), any()))
        .thenReturn(new IntentLlm.Extraction(fresh, IntentLlm.Mode.NONE));

    controller.search(new SearchDtos.AiSearchRequest(query, sessionId), new MockHttpServletRequest(), new MockHttpServletResponse());

    verify(pipeline, times(1)).extractIntent(any(), any(), any(), any());
    verify(pipeline).extractIntent(eq(query), isNull(), isNull(), any());

    ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
    verify(pipeline).search(any(), any(), any(), any(), noteCaptor.capture());
    assertThat(noteCaptor.getValue()).isEqualTo(AiSearchController.FRESH_NOTE);
    verify(sessions).requireOwned(eq(sessionId), any(), any());
  }

  @Test
  void noSessionId_detectorNeverConsulted_singleExtractionWithNoPrior() {
    String query = "single sharing room in goregaon 20k";
    SearchIntent fresh = SearchIntent.builder().budgetMax(20000).build();
    UUID newSessionId = UUID.randomUUID();
    AiSearchSession newSession = mockSession(newSessionId);
    when(sessions.start(any(), any(), any(), any())).thenReturn(newSession);
    when(pipeline.extractIntent(eq(query), isNull(), any(), any()))
        .thenReturn(new IntentLlm.Extraction(fresh, IntentLlm.Mode.NONE));

    controller.search(new SearchDtos.AiSearchRequest(query, null), new MockHttpServletRequest(), new MockHttpServletResponse());

    verifyNoInteractions(detector);
    verify(pipeline, times(1)).extractIntent(any(), any(), any(), any());
    verify(pipeline).extractIntent(eq(query), isNull(), isNull(), any());

    ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
    verify(pipeline).search(any(), any(), any(), any(), noteCaptor.capture());
    assertThat(noteCaptor.getValue()).isNull();
  }
}
