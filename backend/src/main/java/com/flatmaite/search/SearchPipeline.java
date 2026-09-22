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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

  /**
   * Ranked homes plus whether any came from outside the requested localities, and — when the
   * exact-match page came back thin — how many of {@code results} are exact ({@code exactCount})
   * and which tiers topped it up ({@code rescueSummary}, null when the ladder never fired).
   */
  private record Homes(
      List<AiResult> results,
      boolean includesNearby,
      double radiusKm,
      int exactCount,
      String rescueSummary) {}

  /**
   * What walking the fallback ladder added on top of the exact page: the new candidates, which tier
   * introduced each, the reasons to show for the tiers that actually contributed, and the widest
   * ring any of them reached — which is then the honest radius to name in the "nearby areas" note.
   */
  record Rescue(
      Map<UUID, Candidate> added,
      Map<UUID, RescueLadder.SearchTier> tierOf,
      List<String> reasons,
      Double widerRingRadiusKm) {

    static Rescue none() {
      return new Rescue(Map.of(), Map.of(), List.of(), null);
    }
  }

  /** What the note calls a tier that actually contributed rows. */
  static String tierReason(RescueLadder.SearchTier tier) {
    return switch (tier) {
      case EXACT -> "exact matches";
      case NEARBY -> "further out";
      case OVER_BUDGET -> "slightly over budget";
    };
  }

  /**
   * Walks the fallback ladder until the page is no longer thin. Three rules live here and nowhere
   * else, which is why this takes its retrieval as a function and can be tested without a database:
   * a tier that finds nothing new is skipped silently and never named in the summary, the walk
   * stops the moment {@code minResults} distinct listings are in hand, and an exhausted ladder
   * simply returns what it managed to find.
   *
   * <p>{@code tiers} are the ones after {@link RescueLadder.SearchTier#EXACT}, which the caller has
   * already retrieved; {@code alreadyFound} is that exact page, copied and never mutated.
   */
  static Rescue walkLadder(
      List<RescueLadder.Tier> tiers,
      Set<UUID> alreadyFound,
      int minResults,
      java.util.function.BiFunction<RescueLadder.Tier, Double, List<Candidate>> retrieve) {
    Map<UUID, Candidate> added = new LinkedHashMap<>();
    Map<UUID, RescueLadder.SearchTier> tierOf = new LinkedHashMap<>();
    List<String> reasons = new ArrayList<>();
    Set<UUID> seen = new HashSet<>(alreadyFound);
    Double widerRingRadius = null;
    for (RescueLadder.Tier tier : tiers) {
      if (seen.size() >= minResults) {
        break;
      }
      boolean addedAny = false;
      for (Candidate c : retrieve.apply(tier, tier.radiusKm())) {
        if (seen.add(c.id())) {
          added.put(c.id(), c);
          tierOf.put(c.id(), tier.tier());
          addedAny = true;
        }
      }
      // a tier that finds nothing new is skipped silently — the ladder keeps going
      if (addedAny) {
        reasons.add(tierReason(tier.tier()));
        // "within ~N km" is a claim about where these rows came from: the widest ring that
        // actually contributed is the only radius that describes all of them.
        if (tier.radiusKm() > 0 && (widerRingRadius == null || tier.radiusKm() > widerRingRadius)) {
          widerRingRadius = tier.radiusKm();
        }
      }
    }
    return new Rescue(added, tierOf, reasons, widerRingRadius);
  }

  // ------------------------------------------------------------- intent

  public IntentLlm.Extraction extractIntent(String query, SearchIntent prior, UUID userId, String anonKey) {
    long start = System.currentTimeMillis();
    AiFeature feature = prior == null ? AiFeature.INTENT_EXTRACTION : AiFeature.REFINEMENT;

    // 1) zero-cost heuristic refinements
    SearchIntent heuristic = RefinementHeuristics.apply(prior, query);
    if (heuristic != null) {
      usageService.log(userId, anonKey, feature, "heuristic", "regex", 0, 0, true, true,
          System.currentTimeMillis() - start, null);
      // A heuristic refinement is an explicit instruction ("cheaper", "only verified"), and it
      // changes exactly the slot instructed. The new number is our arithmetic, not the user's word,
      // so grading it against the query would demote the clearest thing they said. Carry the grades
      // the conversation already earned.
      SearchIntent resolved = resolveLocalities(heuristic);
      SearchIntent carried =
          resolved.toBuilder().confidence(prior == null ? null : prior.confidence()).build();
      return new IntentLlm.Extraction(carried, IntentLlm.Mode.NONE);
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
    Map<String, Double> merged = carryConfidence(prior, intent, graded);
    return intent.toBuilder().confidence(merged).build();
  }

  /**
   * Combines this turn's grades with the prior turn's. A slot whose value is carried unchanged keeps
   * the stronger of the two grades: the user stated it once and has not taken it back, so a later
   * sentence that simply does not mention it must not demote it to a preference. A slot whose value
   * changed is graded by this turn's words alone.
   */
  static Map<String, Double> carryConfidence(
      SearchIntent prior, SearchIntent next, Map<String, Double> graded) {
    if (prior == null || prior.confidence() == null) {
      return graded;
    }
    Map<String, Double> merged = graded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graded);
    for (Map.Entry<String, Double> e : prior.confidence().entrySet()) {
      if (e.getValue() == null || !ConfidenceGate.sameValue(prior, next, e.getKey())) {
        continue;
      }
      Double current = merged.get(e.getKey());
      merged.put(e.getKey(), current == null ? e.getValue() : Math.max(current, e.getValue()));
    }
    return merged;
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
            ? new Homes(List.of(), false, 0.0, 0, null)
            : searchHomes(intent, intentHash, viewerId, anonKey);
    List<AiResult> flatmates =
        target == SearchTarget.PROPERTIES ? List.of() : searchFlatmates(intent, intentHash, viewerId, anonKey);

    List<Relaxer> relaxers = List.of();
    if (homes.results().isEmpty() && target != SearchTarget.FLATMATES) {
      relaxers = computeRelaxers(intent);
    }

    String finalNote = note;
    if (homes.includesNearby()) {
      String nearby = "Also showing nearby areas within ~%.1f km.".formatted(homes.radiusKm());
      finalNote = note == null ? nearby : note + " " + nearby;
    }

    if (homes.rescueSummary() != null) {
      String rescue =
          "Only %d exact %s — added %d nearby option%s (%s)."
              .formatted(
                  homes.exactCount(),
                  homes.exactCount() == 1 ? "match" : "matches",
                  homes.results().size() - homes.exactCount(),
                  homes.results().size() - homes.exactCount() == 1 ? "" : "s",
                  homes.rescueSummary());
      finalNote = finalNote == null ? rescue : finalNote + " " + rescue;
    }

    List<String> soft = ConfidenceGate.softSlots(intent);
    if (!soft.isEmpty()) {
      String preferences =
          "Some of these are preferences, not filters: %s."
              .formatted(soft.stream().map(ConfidenceGate::label).collect(Collectors.joining(", ")));
      finalNote = finalNote == null ? preferences : finalNote + " " + preferences;
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

  /**
   * Where this search is anchored on the map, which is what decides whether the ladder has a
   * distance tier to offer at all. {@link IntentLocalities} has already bound every place name it
   * could to locality ids, so the anchor is those ids and the point at their centre; a name nothing
   * could place left no id behind and the search is anchored nowhere, which the user is told about
   * rather than being quietly served the whole city.
   *
   * <p>Only a slot that is actually filtering counts. A place the reader merely guessed at is not
   * in the WHERE clause either (see {@link HybridRetriever#admittedLocalityIds}), so there is no
   * ring around it to widen and no honest "within ~N km" to say about the rows. A stated commute is
   * a place too, and it anchors the search when no home area does.
   */
  Placement placementOf(SearchIntent intent) {
    if (ConfidenceGate.isHard(intent, "locations")) {
      Placement home =
          localityResolver.gazetteerPlacementOf(
              retriever.requestedLocalityIds(intent), intent.confidenceOf("locations"));
      if (home.placed()) {
        return home;
      }
    }
    if (ConfidenceGate.isHard(intent, "commuteTo")
        && intent.commuteTo() != null
        && intent.commuteTo().localityId() != null) {
      return localityResolver.gazetteerPlacementOf(
          List.of(intent.commuteTo().localityId()), intent.confidenceOf("commuteTo"));
    }
    return Placement.none();
  }

  private Homes searchHomes(SearchIntent intent, String intentHash, UUID viewerId, String anonKey) {
    List<RescueLadder.Tier> tiers =
        RescueLadder.tiers(intent, placementOf(intent), props.getSearch());
    // Tier 1 is the page itself: the requested placement only, and the budget exactly as stated.
    // Its radius is the ladder's, not a default — a row from five kilometres away is a tier 2 row
    // that says so, never an "exact match" the user has to discover is in another suburb.
    RescueLadder.Tier exact = tiers.get(0);
    Map<UUID, Candidate> byId = new LinkedHashMap<>();
    Map<UUID, RescueLadder.SearchTier> tierOf = new LinkedHashMap<>();
    for (Candidate c : retriever.retrieveListings(exact.intent(), exact.radiusKm())) {
      byId.putIfAbsent(c.id(), c);
      tierOf.putIfAbsent(c.id(), exact.tier());
    }

    // Thin page: walk the rest of the ladder, collecting new ids per tier, stopping as soon as we
    // have enough or the ladder runs out. Retrieval per tier is the only repeated cost — hydration
    // and scoring below run exactly once over the union.
    Rescue rescue = Rescue.none();
    if (tierOf.size() < props.getSearch().getMinResults()) {
      rescue =
          walkLadder(
              tiers.subList(1, tiers.size()),
              tierOf.keySet(),
              props.getSearch().getMinResults(),
              (tier, radiusKm) -> retriever.retrieveListings(tier.intent(), radiusKm));
      byId.putAll(rescue.added());
      tierOf.putAll(rescue.tierOf());
    }
    List<String> rescueReasons = rescue.reasons();

    if (byId.isEmpty()) {
      return new Homes(List.of(), false, 0.0, 0, null);
    }
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
    // ListingCandidate.radiusMinutes is the location score's minutes-based decay denominator — a
    // stated commute cap is already minutes; a locations-based search converts the configured km
    // ring through Mumbai's calibration, the same "how far is far" scale the decay always used.
    int decayMinutes =
        commuteIntent
            ? (intent.commuteTo().maxMinutes() == null
                ? SearchIntent.DEFAULT_COMMUTE_MINUTES
                : intent.commuteTo().maxMinutes())
            : CommuteEstimator.minutesForKm(
                props.getSearch().getNearbyRadiusKm(), props.getGeo().calibrationFor(null));

    record Row(
        Listing listing,
        Candidate candidate,
        Scored scored,
        Integer commute,
        String anchorName,
        boolean inPreferred,
        RescueLadder.SearchTier tier) {}
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
      // anchor is a real, resolved locality id whenever it is non-null (see commuteAnchor/nearestOf
      // above), but nameOf can still come back null if the resolver's cache is stale relative to
      // the id it was handed — "your area" is the same honest placeholder already used when there
      // is no anchor at all, never a wrong city standing in for a place we cannot name. The
      // commuteIntent branch needs the identical guard: commuteTo.place is a plain nullable String
      // on the wire (SearchIntent.CommuteTo), and /apply replays a client-submitted intent straight
      // through this pipeline with no IntentLocalities.resolve pass — a body carrying only
      // commuteTo.localityId (no place) must not render the literal "null" into "~N min to null".
      String anchorName =
          commuteIntent
              ? Objects.requireNonNullElse(intent.commuteTo().place(), "your area")
              : anchor == null
                  ? "your area"
                  : Objects.requireNonNullElse(localityResolver.nameOf(anchor), "your area");
      ListingCandidate candidate =
          new ListingCandidate(
              l,
              c == null ? null : c.localityId(),
              // null rather than a Mumbai default: no candidate data, or a locality id nameOf does
              // not recognise, is honestly "we don't know", not a false statement about Mumbai.
              c == null ? null : localityResolver.nameOf(c.localityId()),
              lister != null && lister.getEmailVerifiedAt() != null,
              lister != null && lister.getPhoneVerifiedAt() != null,
              idVerified.contains(l.getListerId()),
              c == null ? HybridRetriever.Retrieval.NONE : c.retrieval(),
              commuteMinutes,
              anchorName,
              commuteIntent,
              decayMinutes,
              inPreferred);
      rows.add(
          new Row(
              l,
              c,
              MatchScorer.scoreListing(intent, candidate),
              commuteMinutes,
              anchorName,
              inPreferred,
              tierOf.getOrDefault(l.getId(), RescueLadder.SearchTier.EXACT)));
    }
    // Absolute sort, not score-based: every exact row outranks every near miss, whatever the
    // scores. Within a group: score descending as the page does today; near misses additionally in
    // tier order — nearby under budget before nearby slightly over it, which is the order the
    // product states them in. (EXACT ties break on score alone, since every exact row shares it.)
    rows.sort(
        Comparator.comparingInt((Row r) -> r.tier().ordinal())
            .thenComparing(Comparator.comparingInt((Row r) -> r.scored().matchScore()).reversed()));
    List<Row> top = rows.stream().limit(RESULT_LIMIT).toList();
    // "Also showing nearby areas within ~N km" is a claim about where these rows came from, so it
    // is only said when it is true. A soft `locations` puts no locality in the WHERE at all, so the
    // page is Mumbai-wide and no radius describes it — the "preferences, not filters" sentence
    // covers that case and says something the query actually made true. And when a distance tier
    // fired, the rows came from *its* ring, not the configured one.
    boolean includesNearby =
        ConfidenceGate.isHard(intent, "locations")
            && !commuteIntent
            && !preferred.isEmpty()
            && top.stream().anyMatch(r -> r.candidate() != null && !r.inPreferred());
    double nearbyRadiusKm =
        rescue.widerRingRadiusKm() == null ? props.getSearch().getNearbyRadiusKm() : rescue.widerRingRadiusKm();

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

    int exactCount =
        (int) top.stream().filter(r -> r.tier() == RescueLadder.SearchTier.EXACT).count();
    String rescueSummary = rescueReasons.isEmpty() ? null : String.join(", ", rescueReasons);

    List<AiResult> out = new ArrayList<>();
    for (Row r : top) {
      Explanation e = explanations.get(r.listing().getId());
      // a home inside a requested locality needs no distance label; commute intents label everything
      String label =
          r.commute() == null || (!commuteIntent && r.inPreferred())
              ? null
              : "~%d min %s %s (estimate)".formatted(r.commute(), commuteIntent ? "to" : "from", r.anchorName());
      boolean nearMiss = r.tier() != RescueLadder.SearchTier.EXACT;
      String nearMissReason =
          nearMiss
              ? nearMissReason(
                  r.tier(),
                  r.listing().getRentMonthly(),
                  r.commute(),
                  r.anchorName(),
                  commuteIntent,
                  intent)
              : null;
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
              null,
              nearMiss,
              nearMissReason));
    }
    return new Homes(out, includesNearby, nearbyRadiusKm, exactCount, rescueSummary);
  }

  /**
   * Why this listing is on the page although it is not a plain match. Shown to users verbatim, so
   * it states only what this tier actually relaxed and only what we actually know: a distance when
   * one was measured, and the size of the overshoot when the row is in the +10% band. No other
   * relaxation can produce a row here — nothing else is relaxed without the user asking.
   *
   * <p>A tier-3 row is both slightly over budget and (when a place was named) possibly outside it;
   * the money is what its block is headed by, and the distance is on the row's own commute label.
   */
  static String nearMissReason(
      RescueLadder.SearchTier tier,
      Integer rentMonthly,
      Integer commuteMinutes,
      String anchorName,
      boolean commuteIntent,
      SearchIntent intent) {
    if (tier == RescueLadder.SearchTier.OVER_BUDGET) {
      if (rentMonthly == null || intent.budgetMax() == null || rentMonthly <= intent.budgetMax()) {
        // no arithmetic we can stand behind — say the shape of the compromise, not a made-up figure
        return "Slightly over your budget";
      }
      return "₹%,d — ₹%,d over your budget".formatted(rentMonthly, rentMonthly - intent.budgetMax());
    }
    if (commuteMinutes == null) {
      return "Outside your preferred areas";
    }
    return "~%d min %s %s".formatted(commuteMinutes, commuteIntent ? "to" : "from", anchorName);
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
              cards.get(r.fp().getId()),
              false,
              null));
    }
    return out;
  }

  // ------------------------------------------------------------- relaxers

  /** A candidate relaxer paired with the slot it relaxes, so the ladder can be sorted by confidence. */
  private record RelaxerCandidate(Relaxer relaxer, String slot) {}

  /**
   * Slots that already have a hand-written relaxer below, with a better label than a generic one
   * could be ("Raise budget to ₹17,000" beats "Drop the budget filter"). The generic pass skips
   * them rather than offering the same slot twice. {@code commuteTo} and its radius belong to the
   * hand-written location relaxer, which clears both together — {@link RescueLadder#without} maps
   * either of them onto the whole {@code commuteTo}, so a separate "commute time" offer would drop
   * the workplace as well and be mislabelled.
   */
  private static final Set<String> HAND_WRITTEN_RELAXERS =
      Set.of("budgetMax", "budgetMin", "lifestyle", "locations", "commuteTo", "commuteTo.maxMinutes", "verifiedOnly");

  /**
   * "No results" is never a dead end — offer one-click constraint relaxations with real counts.
   * Offered in ascending order of the reader's confidence in the slot each relaxer relaxes: the
   * constraint the reader was least sure of is the one the user will miss least. Ties (equal
   * confidence, including the common case where every slot is a hard 1.0) break by the order the
   * candidates were considered, via a stable sort.
   *
   * <p>Since the fallback ladder stopped dropping filters on its own (WS6 §4.4), this is the only
   * place a filter is ever given up, so every gated slot the user actually stated needs an offer of
   * its own. Without one, the sole way out of an impossible {@code bhk} would be the "Start broader"
   * reset at the bottom, which drops every filter at once — dropping nine constraints to relax one
   * is not a visible relaxation of that one.
   */
  private List<Relaxer> computeRelaxers(SearchIntent intent) {
    List<RelaxerCandidate> candidates = new ArrayList<>();
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
        candidates.add(
            new RelaxerCandidate(
                new Relaxer(
                    "Raise budget to ₹%,d".formatted(relaxed.budgetMax()),
                    "shows %d option%s".formatted(count, count == 1 ? "" : "s"),
                    relaxed,
                    count),
                "budgetMax"));
      }
    }
    if (intent.budgetMin() != null) {
      SearchIntent relaxed = intent.toBuilder().budgetMin(null).build();
      long count = countFor(relaxed);
      if (count > 0) {
        candidates.add(
            new RelaxerCandidate(
                new Relaxer("Lower the minimum", "shows %d more".formatted(count), relaxed, count),
                "budgetMin"));
      }
    }
    if (Boolean.TRUE.equals(intent.verifiedOnly())) {
      SearchIntent relaxed = intent.toBuilder().verifiedOnly(false).build();
      long count = countFor(relaxed);
      if (count > 0) {
        candidates.add(
            new RelaxerCandidate(
                new Relaxer("Include unverified listings", "shows %d more".formatted(count), relaxed, count),
                "verifiedOnly"));
      }
    }
    if (intent.lifestyle() != null) {
      SearchIntent relaxed = intent.toBuilder().lifestyle(null).build();
      long count = countFor(relaxed);
      if (count > 0) {
        candidates.add(
            new RelaxerCandidate(
                new Relaxer("Relax lifestyle filters", "shows %d more".formatted(count), relaxed, count),
                "lifestyle"));
      }
    }
    if ((intent.locations() != null && !intent.locations().isEmpty()) || intent.commuteTo() != null) {
      SearchIntent relaxed = intent.toBuilder().locations(null).commuteTo(null).build();
      long count = countFor(relaxed);
      if (count > 0) {
        candidates.add(
            new RelaxerCandidate(
                new Relaxer("Search all of Mumbai", "shows %d more".formatted(count), relaxed, count),
                "locations"));
      }
    }
    // Every other slot the user stated and that is actually filtering gets its own counted offer,
    // clearing that slot and nothing else. A soft slot is not filtering in the first place, so
    // giving it up would open no doors; an exclusion the user typed is a promise and is never
    // offered at all (verifiedOnly is ALWAYS_HARD too, but has its own hand-written offer above —
    // the user clicking it is the consent the gate exists to require). This runs only on an empty
    // page, and costs one count per stated slot.
    for (String slot : SearchIntent.GATED_SLOTS) {
      if (HAND_WRITTEN_RELAXERS.contains(slot)
          || ConfidenceGate.ALWAYS_HARD.contains(slot)
          || !ConfidenceGate.isPresent(intent, slot)
          || !ConfidenceGate.isHard(intent, slot)) {
        continue;
      }
      SearchIntent relaxed = RescueLadder.without(intent, slot);
      long count = countFor(relaxed);
      if (count > 0) {
        candidates.add(
            new RelaxerCandidate(
                new Relaxer(
                    "Drop the %s filter".formatted(ConfidenceGate.label(slot)),
                    "shows %d more".formatted(count),
                    relaxed,
                    count),
                slot));
      }
    }
    candidates.sort(Comparator.comparingDouble(c -> intent.confidenceOf(c.slot())));
    List<Relaxer> out = new ArrayList<>(candidates.stream().map(RelaxerCandidate::relaxer).toList());
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
