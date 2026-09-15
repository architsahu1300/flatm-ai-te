package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.AiSearchSession;
import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.ratelimit.RateLimiter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Proves the arbiter's decision reaches {@code pipeline.search} with its note — the actual
 * new-vs-refine decision tree is covered by {@link IntentArbiterTest}.
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
    controller = new AiSearchController(pipeline, sessions, usage, rateLimiter, new IntentArbiter(detector));

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(pipeline.search(any(), any(), any(), any(), any())).thenReturn(dummyResponse());
    when(pipeline.search(any(), any(), any(), any())).thenReturn(dummyResponse());
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
    assertThat(noteCaptor.getValue()).isEqualTo(IntentArbiter.FRESH_NOTE);
  }

  @Test
  void applyingChipsEndorsesEverySlot_soNothingStaysAPreference() {
    UUID sessionId = UUID.randomUUID();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    SearchIntent edited =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .confidence(java.util.Map.of("roomType", 0.5))
            .build();

    controller.apply(
        new AiSearchController.ApplyIntentRequest(sessionId, edited),
        new MockHttpServletRequest(),
        new MockHttpServletResponse());

    ArgumentCaptor<SearchIntent> captor = ArgumentCaptor.forClass(SearchIntent.class);
    verify(pipeline).search(captor.capture(), any(), any(), any());
    assertThat(captor.getValue().confidenceOf("roomType")).isEqualTo(1.0);
  }
}
