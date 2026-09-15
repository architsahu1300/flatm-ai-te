package com.flatmaite.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.ai.ExplainerLlm.Explanation;
import com.flatmaite.ai.EmbeddingTextComposer;
import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.AiFeature;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.domain.VerificationType;
import com.flatmaite.flatmate.FlatmateProfile;
import com.flatmaite.flatmate.FlatmateProfileRepository;
import com.flatmaite.flatmate.FlatmateService;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingAssembler;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.search.ExplanationService.Explainable;
import com.flatmaite.search.HybridRetriever.Candidate;
import com.flatmaite.search.MatchScorer.FlatmateCandidate;
import com.flatmaite.search.MatchScorer.ListingCandidate;
import com.flatmaite.search.MatchScorer.Scored;
import com.flatmaite.search.SearchDtos.AiResult;
import com.flatmaite.search.SearchDtos.Relaxer;
import com.flatmaite.user.Profile;
import com.flatmaite.user.ProfileRepository;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import com.flatmaite.verification.Verification;
import com.flatmaite.verification.VerificationRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The product: query → intent → hard filters → hybrid retrieval → deterministic scoring → ranking
 * → grounded explanations. Degradation ladder guarantees the search never 500s because a model
 * misbehaved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchPipeline {

  private static final int RESULT_LIMIT = 20;
  private static final org.slf4j.Logger CONFIDENCE_LOG = org.slf4j.LoggerFactory.getLogger(SearchPipeline.class);

  private final IntentLlm intentLlm;
  private final KeywordIntentParser keywordParser;
  private final HybridRetriever retriever;
  private final ListingQueryService listingQueryService;
  private final ListingAssembler listingAssembler;
  private final FlatmateService flatmateService;
  private final FlatmateProfileRepository flatmateProfiles;
  private final ProfileRepository profiles;
  private final UserRepository users;
  private final VerificationRepository verifications;
  private final CommuteEstimator commuteEstimator;
  private final LocalityResolver localityResolver;
  private final ExplanationService explanationService;
  private final AiUsageService usageService;
  private final ObjectMapper objectMapper;
  private final FlatmaiteProperties props;

  private final Cache<String, IntentLlm.Extraction> intentCache =
      Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(Duration.ofHours(24)).build();

  /** Ranked homes plus whether any came from outside the requested localities. */
  private record Homes(List<AiResult> results, boolean includesNearby, int radiusMinutes) {}

  // ------------------------------------------------------------- intent

  public IntentLlm.Extraction extractIntent(String query, SearchIntent prior, UUID userId, String anonKey) {
    long start = System.currentTimeMillis();
    AiFeature feature = prior == null ? AiFeature.INTENT_EXTRACTION : AiFeature.REFINEMENT;

    // 1) zero-cost heuristic refinements
    SearchIntent heuristic = RefinementHeuristics.apply(prior, query);
    if (heuristic != null) {
      usageService.log(userId, anonKey, feature, "heuristic", "regex", 0, 0, true, true,
          System.currentTimeMillis() - start, null);
      return new IntentLlm.Extraction(
          withConfidence(resolveLocalities(heuristic), query, prior, localityResolver),
          IntentLlm.Mode.NONE);
    }

    // 2) cache
    String cacheKey = cacheKey(query, prior);
    IntentLlm.Extraction cached = intentCache.getIfPresent(cacheKey);
    if (cached != null) {
      usageService.log(userId, anonKey, feature, intentLlm.providerName(), intentLlm.model(), 0, 0, true, true,
          System.currentTimeMillis() - start, cacheKey);
      return cached;
    }

    // 3) LLM (or its mock) with internal repair + keyword fallback
    IntentLlm.Extraction extracted;
    boolean success = true;
    try {
      extracted = intentLlm.extractWithMode(query, prior);
    } catch (Exception e) {
      log.warn("Intent extraction hard-failed, degrading to keyword parse", e);
      extracted = new IntentLlm.Extraction(keywordParser.parse(query), IntentLlm.Mode.NONE);
      success = false;
    }
    IntentLlm.Extraction resolved =
        new IntentLlm.Extraction(
            withConfidence(resolveLocalities(extracted.intent()), query, prior, localityResolver),
            extracted.mode());
    intentCache.put(cacheKey, resolved);
    usageService.log(
        userId,
        anonKey,
        feature,
        intentLlm.providerName(),
        intentLlm.model(),
        AiUsageService.estimateTokens(query) + intentLlm.promptOverheadTokens(),
        AiUsageService.estimateTokens(intentJson(resolved.intent())),
        false,
        success,
        System.currentTimeMillis() - start,
        cacheKey);
    return resolved;
  }

  /** Name → id binding; names no layer can place are surfaced and logged for the eval report. */
  private SearchIntent resolveLocalities(SearchIntent intent) {
    SearchIntent resolved = IntentLocalities.resolve(intent, localityResolver);
    List<String> before = intent.unresolvedLocations() == null ? List.of() : intent.unresolvedLocations();
    if (resolved.unresolvedLocations() != null) {
      for (String name : resolved.unresolvedLocations()) {
        if (!before.contains(name)) {
          log.info("search.unresolved-locality name=\"{}\" query=\"{}\"", name, intent.originalQuery());
        }
      }
    }
    return resolved;
  }

  /**
   * The model may rate its own read of the user's words; that rating can only lower a grade, never
   * raise one. Slots the words never grounded are not resurrected by the model's confidence in them.
   */
  static Map<String, Double> combineConfidence(Map<String, Double> grounding, Map<String, Double> selfRating) {
    if (grounding == null) {
      return null;
    }
    Map<String, Double> out = new LinkedHashMap<>(grounding);
    if (selfRating != null) {
      for (Map.Entry<String, Double> e : selfRating.entrySet()) {
        Double self = e.getValue();
        Double ground = out.get(e.getKey());
        if (self == null || ground == null) {
          continue;
        }
        out.put(e.getKey(), Math.min(ground, Math.max(0.0, Math.min(1.0, self))));
      }
    }
    return out;
  }

  /**
   * Grades an extracted intent against the words that produced it. Runs on every path — LLM, mock,
   * heuristic, cache — so one rule decides what is enforced, whoever did the extracting.
   *
   * <p>Fails closed: if grading throws, the intent comes back with **no** grades at all, so every
   * slot reads 1.0 and stays a hard filter. Returning the intent untouched would not be closed —
   * it may already carry the model's own unvalidated self-rating, or a prior turn's stale grades.
   */
  static SearchIntent withConfidence(
      SearchIntent intent, String query, SearchIntent prior, LocalityResolver resolver) {
    if (intent == null) {
      return null;
    }
    Map<String, Double> graded;
    try {
      graded = combineConfidence(IntentGrounding.score(intent, query, resolver), intent.confidence());
    } catch (RuntimeException e) {
      CONFIDENCE_LOG.warn("Confidence grading failed, keeping every slot hard: {}", e.getMessage());
      return intent.toBuilder().confidence(null).build();
    }
    Map<String, Double> merged =
        prior == null ? graded : SearchIntent.mergeConfidence(prior.confidence(), graded);
    return intent.toBuilder().confidence(merged).build();
  }

  // ------------------------------------------------------------- search

  @Transactional(readOnly = true)
  public SearchDtos.AiSearchResponse search(SearchIntent intent, UUID viewerId, String anonKey, UUID sessionId) {
    return search(intent, viewerId, anonKey, sessionId, null);
  }

  @Transactional(readOnly = true)
  public SearchDtos.AiSearchResponse search(
      SearchIntent intent, UUID viewerId, String anonKey, UUID sessionId, String note) {
    String intentHash = EmbeddingTextComposer.sha256(intentJson(intent));
    SearchTarget target = intent.targetOrDefault();

    Homes homes =
        target == SearchTarget.FLATMATES
            ? new Homes(List.of(), false, 0)
            : searchHomes(intent, intentHash, viewerId, anonKey);
    List<AiResult> flatmates =
        target == SearchTarget.PROPERTIES ? List.of() : searchFlatmates(intent, intentHash, viewerId, anonKey);

    List<Relaxer> relaxers = List.of();
    if (homes.results().isEmpty() && target != SearchTarget.FLATMATES) {
      relaxers = computeRelaxers(intent);
    }

    String finalNote = note;
    if (homes.includesNearby()) {
      String nearby = "Also showing nearby areas within ~%d min.".formatted(homes.radiusMinutes());
      finalNote = note == null ? nearby : note + " " + nearby;
    }

    return new SearchDtos.AiSearchResponse(
        sessionId,
        intent,
        explanationService.usesLlm() ? explanationService.providerName() : "mock",
        homes.results(),
        flatmates,
        relaxers,
        finalNote);
  }

  private Homes searchHomes(SearchIntent intent, String intentHash, UUID viewerId, String anonKey) {
    List<Candidate> candidates = retriever.retrieveListings(intent);
    if (candidates.isEmpty()) {
      return new Homes(List.of(), false, 0);
    }
    Map<UUID, Candidate> byId = new LinkedHashMap<>();
    candidates.forEach(c -> byId.put(c.id(), c));
    List<Listing> hydrated = listingQueryService.hydrate(new ArrayList<>(byId.keySet()));

    // batch verification + user flags for listers
    List<UUID> listerIds = hydrated.stream().map(Listing::getListerId).distinct().toList();
    Map<UUID, User> userById = new HashMap<>();
    users.findAllById(listerIds).forEach(u -> userById.put(u.getId(), u));
    Set<UUID> idVerified = new HashSet<>();
    for (Verification v : verifications.findByUserIdIn(listerIds)) {
      if (v.getStatus() == VerificationStatus.VERIFIED
          && (v.getType() == VerificationType.GOV_ID || v.getType() == VerificationType.SELFIE)) {
        idVerified.add(v.getUserId());
      }
    }

    // requested localities (not the widened set) — distances are measured from the nearest one
    Set<UUID> preferred = new HashSet<>(retriever.requestedLocalityIds(intent));
    boolean commuteIntent = intent.commuteTo() != null && intent.commuteTo().localityId() != null;
    UUID commuteAnchor = commuteIntent ? intent.commuteTo().localityId() : null;
    int radius =
        commuteIntent
            ? (intent.commuteTo().maxMinutes() == null
                ? SearchIntent.DEFAULT_COMMUTE_MINUTES
                : intent.commuteTo().maxMinutes())
            : props.getSearch().getNearbyRadiusMinutes();

    record Row(Listing listing, Candidate candidate, Scored scored, Integer commute, String anchorName, boolean inPreferred) {}
    List<Row> rows = new ArrayList<>();
    for (Listing l : hydrated) {
      Candidate c = byId.get(l.getId());
      User lister = userById.get(l.getListerId());
      boolean inPreferred = c != null && preferred.contains(c.localityId());
      UUID anchor = commuteAnchor;
      Integer commuteMinutes = null;
      if (c != null) {
        if (anchor == null && !preferred.isEmpty()) {
          anchor = nearestOf(preferred, c);
        }
        if (anchor != null) {
          commuteMinutes =
              c.lat() != null
                  ? commuteEstimator.minutesFromPoint(c.lat(), c.lng(), anchor)
                  : commuteEstimator.minutesBetween(c.localityId(), anchor);
        }
      }
      String anchorName =
          commuteIntent
              ? intent.commuteTo().place()
              : anchor == null ? "your area" : localityResolver.nameOf(anchor);
      ListingCandidate candidate =
          new ListingCandidate(
              l,
              c == null ? null : c.localityId(),
              c == null ? "Mumbai" : localityResolver.nameOf(c.localityId()),
              lister != null && lister.getEmailVerifiedAt() != null,
              lister != null && lister.getPhoneVerifiedAt() != null,
              idVerified.contains(l.getListerId()),
              c == null ? HybridRetriever.Retrieval.NONE : c.retrieval(),
              commuteMinutes,
              anchorName,
              commuteIntent,
              radius,
              inPreferred);
      rows.add(new Row(l, c, MatchScorer.scoreListing(intent, candidate), commuteMinutes, anchorName, inPreferred));
    }
    rows.sort((a, b) -> Integer.compare(b.scored().matchScore(), a.scored().matchScore()));
    List<Row> top = rows.stream().limit(RESULT_LIMIT).toList();
    boolean includesNearby =
        !commuteIntent && !preferred.isEmpty() && top.stream().anyMatch(r -> r.candidate() != null && !r.inPreferred());

    long llmStart = System.currentTimeMillis();
    Map<UUID, Explanation> explanations =
        explanationService.explain(
            intent,
            intentHash,
            top.stream()
                .map(r -> new Explainable(r.listing().getId(), r.listing().getTitle(), r.scored(), r.listing().getUpdatedAt()))
                .toList());
    if (explanationService.usesLlm()) {
      usageService.log(viewerId, anonKey, AiFeature.EXPLANATION, explanationService.providerName(), explanationService.modelName(),
          Math.min(top.size(), ExplanationService.LLM_TOP_N) * 220 + 400,
          Math.min(top.size(), ExplanationService.LLM_TOP_N) * 90,
          false, true, System.currentTimeMillis() - llmStart, intentHash);
    }

    Map<UUID, com.flatmaite.listing.ListingDtos.CardResponse> cards = new HashMap<>();
    listingAssembler.toCards(top.stream().map(Row::listing).toList()).forEach(card -> cards.put(card.id(), card));

    List<AiResult> out = new ArrayList<>();
    for (Row r : top) {
      Explanation e = explanations.get(r.listing().getId());
      // a home inside a requested locality needs no distance label; commute intents label everything
      String label =
          r.commute() == null || (!commuteIntent && r.inPreferred())
              ? null
              : "~%d min %s %s (estimate)".formatted(r.commute(), commuteIntent ? "to" : "from", r.anchorName());
      out.add(
          new AiResult(
              "home",
              r.scored().matchScore(),
              r.scored().breakdown(),
              e == null ? List.of() : e.matchReasons(),
              e == null ? List.of() : e.concerns(),
              label == null ? null : r.commute(),
              label,
              cards.get(r.listing().getId()),
              null));
    }
    return new Homes(out, includesNearby, radius);
  }

  /** The requested locality this candidate is closest to (by the commute estimate). */
  private UUID nearestOf(Set<UUID> preferred, Candidate c) {
    UUID best = null;
    int bestMinutes = Integer.MAX_VALUE;
    for (UUID id : preferred) {
      Integer m =
          c.lat() != null
              ? commuteEstimator.minutesFromPoint(c.lat(), c.lng(), id)
              : commuteEstimator.minutesBetween(c.localityId(), id);
      if (m != null && m < bestMinutes) {
        bestMinutes = m;
        best = id;
      }
    }
    // every seeded locality has a centroid, so `best` is never null in practice; if a locality
    // ever lacks one this picks an arbitrary member of `preferred` rather than failing the search.
    return best == null ? preferred.iterator().next() : best;
  }

  private List<AiResult> searchFlatmates(SearchIntent intent, String intentHash, UUID viewerId, String anonKey) {
    List<Candidate> candidates = retriever.retrieveFlatmates(intent, viewerId);
    if (candidates.isEmpty()) {
      return List.of();
    }
    Map<UUID, Candidate> byId = new LinkedHashMap<>();
    candidates.forEach(c -> byId.put(c.id(), c));
    List<FlatmateProfile> hydrated = new ArrayList<>();
    flatmateProfiles.findAllById(byId.keySet()).forEach(hydrated::add);

    Map<UUID, Profile> profileByUser = new HashMap<>();
    profiles.findAll().forEach(p -> profileByUser.put(p.getUserId(), p));
    Map<UUID, User> userById = new HashMap<>();
    users.findAllById(hydrated.stream().map(FlatmateProfile::getUserId).toList())
        .forEach(u -> userById.put(u.getId(), u));
    Set<UUID> idVerified = new HashSet<>();
    for (Verification v :
        verifications.findByUserIdIn(hydrated.stream().map(FlatmateProfile::getUserId).toList())) {
      if (v.getStatus() == VerificationStatus.VERIFIED
          && (v.getType() == VerificationType.GOV_ID || v.getType() == VerificationType.SELFIE)) {
        idVerified.add(v.getUserId());
      }
    }
    Profile viewerProfile = viewerId == null ? null : profileByUser.get(viewerId);
    Set<UUID> wantedLocalities = new HashSet<>(retriever.requestedLocalityIds(intent));

    record Row(FlatmateProfile fp, Scored scored) {}
    List<Row> rows = new ArrayList<>();
    for (FlatmateProfile fp : hydrated) {
      Candidate c = byId.get(fp.getId());
      User u = userById.get(fp.getUserId());
      double locationOverlap = 0.5;
      if (!wantedLocalities.isEmpty()) {
        Set<UUID> theirs = new HashSet<>(List.of(fp.getLocalityIds()));
        Set<UUID> union = new HashSet<>(wantedLocalities);
        union.addAll(theirs);
        Set<UUID> inter = new HashSet<>(wantedLocalities);
        inter.retainAll(theirs);
        locationOverlap = union.isEmpty() ? 0 : (double) inter.size() / union.size();
      }
      Profile p = profileByUser.get(fp.getUserId());
      FlatmateCandidate candidate =
          new FlatmateCandidate(
              fp,
              p,
              u != null && u.getEmailVerifiedAt() != null,
              u != null && u.getPhoneVerifiedAt() != null,
              idVerified.contains(fp.getUserId()),
              c == null ? HybridRetriever.Retrieval.NONE : c.retrieval(),
              locationOverlap,
              p == null ? 0.3 : p.getProfileCompleteness() / 100.0);
      rows.add(new Row(fp, MatchScorer.scoreFlatmate(intent, viewerProfile, candidate)));
    }
    rows.sort((a, b) -> Integer.compare(b.scored().matchScore(), a.scored().matchScore()));
    List<Row> top = rows.stream().limit(RESULT_LIMIT).toList();

    Map<UUID, Explanation> explanations =
        explanationService.explain(
            intent,
            intentHash,
            top.stream()
                .map(r -> new Explainable(r.fp().getId(), r.fp().getHeadline(), r.scored(), r.fp().getUpdatedAt()))
                .toList());

    Map<UUID, com.flatmaite.flatmate.FlatmateDtos.CardResponse> cards = new HashMap<>();
    flatmateService
        .assembleCards(top.stream().map(Row::fp).toList(), viewerId)
        .forEach(card -> cards.put(card.id(), card));

    List<AiResult> out = new ArrayList<>();
    for (Row r : top) {
      Explanation e = explanations.get(r.fp().getId());
      out.add(
          new AiResult(
              "flatmate",
              r.scored().matchScore(),
              r.scored().breakdown(),
              e == null ? List.of() : e.matchReasons(),
              e == null ? List.of() : e.concerns(),
              null,
              null,
              null,
              cards.get(r.fp().getId())));
    }
    return out;
  }

  // ------------------------------------------------------------- relaxers

  /** "No results" is never a dead end — offer one-click constraint relaxations with real counts. */
  private List<Relaxer> computeRelaxers(SearchIntent intent) {
    List<Relaxer> out = new ArrayList<>();
    if (intent.budgetMax() != null) {
      SearchIntent relaxed = intent.toBuilder().budgetMax((int) (intent.budgetMax() * 1.2)).build();
      long count = countFor(relaxed);
      if (count == 0) {
        // +20% isn't enough — find the cheapest listing that matches everything else
        SearchIntent uncapped = intent.toBuilder().budgetMax(null).build();
        Integer cheapest = cheapestRentFor(uncapped);
        if (cheapest != null) {
          int suggested = (int) (Math.ceil(cheapest / 500.0) * 500);
          relaxed = intent.toBuilder().budgetMax(suggested).build();
          count = countFor(relaxed);
        }
      }
      if (count > 0) {
        out.add(
            new Relaxer(
                "Raise budget to ₹%,d".formatted(relaxed.budgetMax()),
                "shows %d option%s".formatted(count, count == 1 ? "" : "s"),
                relaxed,
                count));
      }
    }
    if (intent.budgetMin() != null) {
      SearchIntent relaxed = intent.toBuilder().budgetMin(null).build();
      long count = countFor(relaxed);
      if (count > 0) {
        out.add(new Relaxer("Lower the minimum", "shows %d more".formatted(count), relaxed, count));
      }
    }
    if (Boolean.TRUE.equals(intent.verifiedOnly())) {
      SearchIntent relaxed = intent.toBuilder().verifiedOnly(false).build();
      long count = countFor(relaxed);
      if (count > 0) {
        out.add(new Relaxer("Include unverified listings", "shows %d more".formatted(count), relaxed, count));
      }
    }
    if (intent.lifestyle() != null) {
      SearchIntent relaxed = intent.toBuilder().lifestyle(null).build();
      long count = countFor(relaxed);
      if (count > 0) {
        out.add(new Relaxer("Relax lifestyle filters", "shows %d more".formatted(count), relaxed, count));
      }
    }
    if ((intent.locations() != null && !intent.locations().isEmpty()) || intent.commuteTo() != null) {
      SearchIntent relaxed = intent.toBuilder().locations(null).commuteTo(null).build();
      long count = countFor(relaxed);
      if (count > 0) {
        out.add(new Relaxer("Search all of Mumbai", "shows %d more".formatted(count), relaxed, count));
      }
    }
    if (out.isEmpty()) {
      // constraints compound — offer one honest broad reset, keeping only the location
      SearchIntent broad =
          SearchIntent.builder()
              .searchTarget(intent.targetOrDefault())
              .locations(intent.locations())
              .commuteTo(intent.commuteTo())
              .excludeLocations(intent.excludeLocations())
              .freeText(intent.freeText())
              .originalQuery(intent.originalQuery())
              .build();
      long count = countFor(broad);
      if (count == 0 && (broad.locations() != null || broad.commuteTo() != null)) {
        broad = broad.toBuilder().locations(null).commuteTo(null).build();
        count = countFor(broad);
      }
      if (count > 0) {
        out.add(
            new Relaxer(
                "Start broader",
                "drop the strict filters — %d homes available".formatted(count),
                broad,
                count));
      }
    }
    return out;
  }

  private long countFor(SearchIntent intent) {
    ListingFilters filters = retriever.toFilters(intent);
    return listingQueryService.findIds(filters, ListingQueryService.Sort.NEWEST, 0, 1).total();
  }

  private Integer cheapestRentFor(SearchIntent intent) {
    ListingFilters filters = retriever.toFilters(intent);
    var idPage = listingQueryService.findIds(filters, ListingQueryService.Sort.PRICE_ASC, 0, 1);
    if (idPage.ids().isEmpty()) {
      return null;
    }
    return listingQueryService.hydrate(idPage.ids()).stream()
        .findFirst()
        .map(Listing::getRentMonthly)
        .orElse(null);
  }

  // ------------------------------------------------------------- helpers

  @SneakyThrows
  public String intentJson(SearchIntent intent) {
    return objectMapper.writeValueAsString(intent);
  }

  private static String cacheKey(String query, SearchIntent prior) {
    String normalized = query.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    String priorPart = prior == null ? "" : String.valueOf(prior.hashCode());
    return EmbeddingTextComposer.sha256(normalized + "|" + priorPart);
  }
}
