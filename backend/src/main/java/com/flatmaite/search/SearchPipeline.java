package com.flatmaite.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.ai.ExplainerLlm.Explanation;
import com.flatmaite.ai.EmbeddingTextComposer;
import com.flatmaite.ai.IntentLlm;
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

  private final Cache<String, SearchIntent> intentCache =
      Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(Duration.ofHours(24)).build();

  // ------------------------------------------------------------- intent

  public SearchIntent extractIntent(String query, SearchIntent prior, UUID userId, String anonKey) {
    long start = System.currentTimeMillis();
    AiFeature feature = prior == null ? AiFeature.INTENT_EXTRACTION : AiFeature.REFINEMENT;

    // 1) zero-cost heuristic refinements
    SearchIntent heuristic = RefinementHeuristics.apply(prior, query);
    if (heuristic != null) {
      usageService.log(userId, anonKey, feature, "heuristic", "regex", 0, 0, true, true,
          System.currentTimeMillis() - start, null);
      return resolveLocalities(heuristic);
    }

    // 2) cache
    String cacheKey = cacheKey(query, prior);
    SearchIntent cached = intentCache.getIfPresent(cacheKey);
    if (cached != null) {
      usageService.log(userId, anonKey, feature, intentLlm.providerName(), intentLlm.model(), 0, 0, true, true,
          System.currentTimeMillis() - start, cacheKey);
      return cached;
    }

    // 3) LLM (or its mock) with internal repair + keyword fallback
    SearchIntent extracted;
    boolean success = true;
    try {
      extracted = intentLlm.extract(query, prior);
    } catch (Exception e) {
      log.warn("Intent extraction hard-failed, degrading to keyword parse", e);
      extracted = keywordParser.parse(query);
      success = false;
    }
    SearchIntent resolved = resolveLocalities(extracted);
    intentCache.put(cacheKey, resolved);
    usageService.log(
        userId,
        anonKey,
        feature,
        intentLlm.providerName(),
        intentLlm.model(),
        AiUsageService.estimateTokens(query) + 700,
        AiUsageService.estimateTokens(intentJson(resolved)),
        false,
        success,
        System.currentTimeMillis() - start,
        cacheKey);
    return resolved;
  }

  /** Deterministic name→id resolution; unresolved names stay as free-text signal only. */
  private SearchIntent resolveLocalities(SearchIntent intent) {
    if (intent.locations() == null && intent.commuteTo() == null) {
      return intent;
    }
    List<SearchIntent.LocationRef> resolved = null;
    if (intent.locations() != null) {
      resolved = new ArrayList<>();
      for (SearchIntent.LocationRef ref : intent.locations()) {
        UUID id = ref.localityId() != null ? ref.localityId() : localityResolver.resolve(ref.name());
        resolved.add(new SearchIntent.LocationRef(ref.name(), id));
      }
    }
    SearchIntent.CommuteTo commute = intent.commuteTo();
    if (commute != null && commute.localityId() == null) {
      commute =
          new SearchIntent.CommuteTo(
              commute.place(), localityResolver.resolve(commute.place()), commute.maxMinutes());
    }
    return intent.toBuilder().locations(resolved).commuteTo(commute).build();
  }

  // ------------------------------------------------------------- search

  @Transactional(readOnly = true)
  public SearchDtos.AiSearchResponse search(SearchIntent intent, UUID viewerId, String anonKey, UUID sessionId) {
    String intentHash = EmbeddingTextComposer.sha256(intentJson(intent));
    SearchTarget target = intent.targetOrDefault();

    List<AiResult> homes =
        target == SearchTarget.FLATMATES ? List.of() : searchHomes(intent, intentHash, viewerId, anonKey);
    List<AiResult> flatmates =
        target == SearchTarget.PROPERTIES ? List.of() : searchFlatmates(intent, intentHash, viewerId, anonKey);

    List<Relaxer> relaxers = List.of();
    if (homes.isEmpty() && target != SearchTarget.FLATMATES) {
      relaxers = computeRelaxers(intent);
    }

    return new SearchDtos.AiSearchResponse(
        sessionId,
        intent,
        explanationService.usesLlm() ? explanationService.providerName() : "mock",
        homes,
        flatmates,
        relaxers,
        null);
  }

  private List<AiResult> searchHomes(SearchIntent intent, String intentHash, UUID viewerId, String anonKey) {
    List<Candidate> candidates = retriever.retrieveListings(intent);
    if (candidates.isEmpty()) {
      return List.of();
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

    // preferred localities (not the commute-expanded set)
    Set<UUID> preferred = new HashSet<>();
    if (intent.locations() != null) {
      intent.locations().forEach(l -> {
        if (l.localityId() != null) preferred.add(l.localityId());
      });
    }
    UUID commuteAnchor =
        intent.commuteTo() == null
            ? (preferred.isEmpty() ? null : preferred.iterator().next())
            : intent.commuteTo().localityId();

    record Row(Listing listing, Candidate candidate, Scored scored, Integer commute) {}
    List<Row> rows = new ArrayList<>();
    for (Listing l : hydrated) {
      Candidate c = byId.get(l.getId());
      User lister = userById.get(l.getListerId());
      Integer commuteMinutes = null;
      if (commuteAnchor != null && c != null) {
        commuteMinutes =
            c.lat() != null
                ? commuteEstimator.minutesFromPoint(c.lat(), c.lng(), commuteAnchor)
                : commuteEstimator.minutesBetween(c.localityId(), commuteAnchor);
      }
      ListingCandidate candidate =
          new ListingCandidate(
              l,
              c == null ? null : c.localityId(),
              c == null ? "Mumbai" : localityResolver.nameOf(c.localityId()),
              lister != null && lister.getEmailVerifiedAt() != null,
              lister != null && lister.getPhoneVerifiedAt() != null,
              idVerified.contains(l.getListerId()),
              c == null ? null : c.cosineSim(),
              commuteMinutes,
              c != null && preferred.contains(c.localityId()));
      rows.add(new Row(l, c, MatchScorer.scoreListing(intent, candidate), commuteMinutes));
    }
    rows.sort((a, b) -> Integer.compare(b.scored().matchScore(), a.scored().matchScore()));
    List<Row> top = rows.stream().limit(RESULT_LIMIT).toList();

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

    String commutePlace = intent.commuteTo() == null ? null : intent.commuteTo().place();
    List<AiResult> out = new ArrayList<>();
    for (Row r : top) {
      Explanation e = explanations.get(r.listing().getId());
      out.add(
          new AiResult(
              "home",
              r.scored().matchScore(),
              r.scored().breakdown(),
              e == null ? List.of() : e.matchReasons(),
              e == null ? List.of() : e.concerns(),
              r.commute(),
              r.commute() == null ? null : "~%d min to %s (estimate)".formatted(r.commute(), commutePlace == null ? "your area" : commutePlace),
              cards.get(r.listing().getId()),
              null));
    }
    return out;
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
    Set<UUID> wantedLocalities = new HashSet<>(retriever.admittedLocalityIds(intent));

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
              c == null ? null : c.cosineSim(),
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
