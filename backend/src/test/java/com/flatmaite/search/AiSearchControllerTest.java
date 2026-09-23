package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flatmaite.ai.AiSearchSession;
import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.UserRole;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.listing.PropertyRepository;
import com.flatmaite.user.ProfileRepository;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Proves the arbiter's decision reaches {@code pipeline.search} with its note — the actual
 * new-vs-refine decision tree is covered by {@link IntentArbiterTest} — and that the city scope
 * the viewer's profile implies reaches both intent extraction and the search (spec §4.11).
 */
class AiSearchControllerTest {

  private static final UUID KANDIVALI = UUID.nameUUIDFromBytes("Kandivali".getBytes());

  private SearchPipeline pipeline;
  private SearchSessionService sessions;
  private AiUsageService usage;
  private RateLimiter rateLimiter;
  private NewQueryDetector detector;
  private ProfileRepository profiles;
  private AiSearchController controller;

  @BeforeEach
  void setUp() {
    pipeline = mock(SearchPipeline.class);
    sessions = mock(SearchSessionService.class);
    usage = mock(AiUsageService.class);
    rateLimiter = mock(RateLimiter.class);
    detector = mock(NewQueryDetector.class);
    profiles = mock(ProfileRepository.class);

    Locality kandivali = Locality.builder().name("Kandivali").city("Mumbai").lat(19.2045).lng(72.8519).build();
    kandivali.setId(KANDIVALI);
    LocalityRepository localityRepo = mock(LocalityRepository.class);
    when(localityRepo.findAll()).thenReturn(List.of(kandivali));
    LocalityResolver localities = new LocalityResolver(localityRepo, mock(PropertyRepository.class));
    localities.load();

    controller =
        new AiSearchController(
            pipeline,
            sessions,
            usage,
            rateLimiter,
            new IntentArbiter(detector),
            new ViewerCityScope(profiles, localities));

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    // the controller calls exactly one overload — the scoped one — on every endpoint
    when(pipeline.search(any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(dummyResponse());
  }

  @AfterEach
  void clearPrincipal() {
    SecurityContextHolder.clearContext();
  }

  /** Signs a viewer in for the duration of one test, the way the security filter would. */
  private static UUID signIn() {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, UserRole.USER, "Test Viewer"), null, List.of()));
    return userId;
  }

  private static SearchDtos.AiSearchResponse dummyResponse() {
    return new SearchDtos.AiSearchResponse(
        null, null, "mock", List.of(), List.of(), List.of(), null, null, List.of(), null);
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
    when(pipeline.extractIntent(eq(query), priorCaptor.capture(), any(), any(), any()))
        .thenReturn(new IntentLlm.Extraction(prior, IntentLlm.Mode.NEW))
        .thenReturn(new IntentLlm.Extraction(freshIntent, IntentLlm.Mode.NONE));

    controller.search(new SearchDtos.AiSearchRequest(query, sessionId), new MockHttpServletRequest(), new MockHttpServletResponse());

    // first call carried the prior; the second (re-extraction) carried none
    assertThat(priorCaptor.getAllValues()).containsExactly(prior, null);

    ArgumentCaptor<SearchIntent> searchIntentCaptor = ArgumentCaptor.forClass(SearchIntent.class);
    ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
    verify(pipeline)
        .search(searchIntentCaptor.capture(), any(), any(), any(), noteCaptor.capture(), anyBoolean(), any());
    assertThat(searchIntentCaptor.getValue()).isEqualTo(freshIntent);
    assertThat(noteCaptor.getValue()).isEqualTo(IntentArbiter.FRESH_NOTE);
  }

  // ---- the city scope a search runs in (spec §4.11) ----

  /** Runs one anonymous /search and returns the scope the pipeline was handed. */
  private CityScope scopeOfASearch() {
    when(detector.decide(anyString())).thenReturn(NewQueryDetector.Verdict.NEW);
    when(pipeline.extractIntent(anyString(), any(), any(), any(), any()))
        .thenReturn(new IntentLlm.Extraction(SearchIntent.builder().build(), IntentLlm.Mode.NEW));
    // built before the stubbing call: mockSession stubs too, and Mockito refuses a nested one
    AiSearchSession fresh = mockSession(UUID.randomUUID());
    when(sessions.start(any(), any(), any(), any())).thenReturn(fresh);

    controller.search(
        new SearchDtos.AiSearchRequest("private room under 20k", null),
        new MockHttpServletRequest(),
        new MockHttpServletResponse());

    ArgumentCaptor<CityScope> captor = ArgumentCaptor.forClass(CityScope.class);
    verify(pipeline).search(any(), any(), any(), any(), any(), anyBoolean(), captor.capture());
    // resolution is scoped too, not just the response — that is the whole point of R9
    verify(pipeline).extractIntent(anyString(), any(), any(), any(), eq(captor.getValue()));
    return captor.getValue();
  }

  @Test
  void theViewersProfileLocalityIsTheCityTheSearchRunsIn() {
    UUID userId = signIn();
    when(profiles.findCurrentLocalityId(userId)).thenReturn(List.of(KANDIVALI));

    CityScope scope = scopeOfASearch();

    assertThat(scope.source()).isEqualTo(CityScope.Source.PROFILE);
    assertThat(scope.city()).isEqualTo("Mumbai");
    assertThat(scope.isSet()).isTrue();
  }

  @Test
  void aViewerWithNoProfileLocalityHasNoCity_ratherThanMumbai() {
    UUID userId = signIn();
    // the row exists, the column is null — JPA hands back a single-element list holding null
    when(profiles.findCurrentLocalityId(userId)).thenReturn(Arrays.asList((UUID) null));

    CityScope scope = scopeOfASearch();

    assertThat(scope.source()).isEqualTo(CityScope.Source.UNSET);
    assertThat(scope.city()).isNull();
  }

  @Test
  void aViewerWithNoProfileAtAllHasNoCity() {
    UUID userId = signIn();
    when(profiles.findCurrentLocalityId(userId)).thenReturn(List.of());

    assertThat(scopeOfASearch().source()).isEqualTo(CityScope.Source.UNSET);
  }

  @Test
  void anAnonymousSearcherHasNoCity_andIsStillServed() {
    // anonymous search is first-class: no principal, no profile lookup, and an explicit UNSET
    assertThat(scopeOfASearch().source()).isEqualTo(CityScope.Source.UNSET);
    verify(profiles, never()).findCurrentLocalityId(any());
  }

  @Test
  void aProfileLocalityTheGazetteerCannotNameIsNoCity_notMumbai() {
    UUID userId = signIn();
    when(profiles.findCurrentLocalityId(userId)).thenReturn(List.of(UUID.randomUUID()));

    CityScope scope = scopeOfASearch();

    assertThat(scope.source()).isEqualTo(CityScope.Source.UNSET);
    assertThat(scope.city()).isNull();
  }

  /** Runs /apply against a session holding {@code prior} and returns the intent the pipeline saw. */
  private SearchIntent applied(SearchIntent prior, SearchIntent edited) {
    UUID sessionId = UUID.randomUUID();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    when(sessions.intentOf(session)).thenReturn(prior);

    controller.apply(
        new AiSearchController.ApplyIntentRequest(sessionId, edited),
        new MockHttpServletRequest(),
        new MockHttpServletResponse());

    ArgumentCaptor<SearchIntent> captor = ArgumentCaptor.forClass(SearchIntent.class);
    // escalated=false: the ordinary path. The escalated one is a different argument, not a
    // different overload, so every /apply assertion below states which of the two it expects.
    verify(pipeline).search(captor.capture(), any(), any(), any(), isNull(), eq(false), any());
    return captor.getValue();
  }

  @Test
  void applyingAChangedSlotEndorsesIt_soItStopsBeingAPreference() {
    SearchIntent prior =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .confidence(java.util.Map.of("roomType", 0.5))
            .build();
    // the user edited the room-type chip: they are the author of the new value
    SearchIntent edited =
        SearchIntent.builder()
            .roomType(RoomType.PRIVATE)
            .confidence(java.util.Map.of("roomType", 0.5))
            .build();

    assertThat(applied(prior, edited).confidenceOf("roomType")).isEqualTo(1.0);
  }

  @Test
  void removingOneChipLeavesTheOtherSoftSlotsSoft() {
    SearchIntent prior =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .maxDeposit(50000)
            .confidence(java.util.Map.of("roomType", 0.5, "maxDeposit", 1.0))
            .build();
    // the user removed the deposit chip and touched nothing else
    SearchIntent edited = prior.toBuilder().maxDeposit(null).build();

    SearchIntent seen = applied(prior, edited);

    assertThat(seen.confidenceOf("roomType")).isEqualTo(0.5);
    assertThat(ConfidenceGate.isHard(seen, "roomType")).isFalse();
  }

  @Test
  void aSlotTheAppliedIntentDoesNotCarry_isNotGraded() {
    SearchIntent prior =
        SearchIntent.builder()
            .roomType(RoomType.ENTIRE)
            .maxDeposit(50000)
            .confidence(java.util.Map.of("roomType", 0.5, "maxDeposit", 0.5))
            .build();
    SearchIntent edited = prior.toBuilder().maxDeposit(null).build();

    assertThat(applied(prior, edited).confidence()).containsOnlyKeys("roomType");
  }

  @Test
  void theEndorsementIsWrittenOut_soTheNextTurnCanCarryIt() {
    // an empty map makes carryConfidence early-return: the endorsement must be explicit 1.0s
    SearchIntent edited = SearchIntent.builder().roomType(RoomType.ENTIRE).build();

    assertThat(applied(null, edited).confidence()).containsEntry("roomType", 1.0);
  }

  @Test
  void clickingRaiseBudget_takesTheEscalatedPath_notTheOrdinaryOverload() {
    SearchIntent prior = SearchIntent.builder().budgetMax(15000).build();
    SearchIntent edited = SearchIntent.builder().budgetMax(17000).build();

    UUID sessionId = UUID.randomUUID();
    AiSearchSession session = mockSession(sessionId);
    when(sessions.requireOwned(eq(sessionId), any(), any())).thenReturn(session);
    when(sessions.intentOf(session)).thenReturn(prior);

    controller.apply(
        new AiSearchController.ApplyIntentRequest(sessionId, edited),
        new MockHttpServletRequest(),
        new MockHttpServletResponse());

    // escalated=true — never the ordinary path this same click would otherwise take
    verify(pipeline).search(any(), any(), any(), any(), isNull(), eq(true), any());
    verify(pipeline, never()).search(any(), any(), any(), any(), isNull(), eq(false), any());
  }

  @Test
  void loweringTheBudgetOnApply_neverEscalates() {
    // a relaxer/chip edit that happens to touch budgetMax without raising it must stay on the
    // ordinary path — applied() itself verifies the plain 4-arg overload was the one called
    SearchIntent prior = SearchIntent.builder().budgetMax(15000).build();
    applied(prior, prior.toBuilder().budgetMax(12000).build());
  }

  @Test
  void keepingTheBudgetUnchangedOnApply_neverEscalates() {
    SearchIntent prior = SearchIntent.builder().budgetMax(15000).build();
    applied(prior, prior.toBuilder().budgetMax(15000).build());
  }
}
