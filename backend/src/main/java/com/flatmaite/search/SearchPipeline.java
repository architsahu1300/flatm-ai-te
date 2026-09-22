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
import com.flatmaite.search.SearchDtos.Choice;
import com.flatmaite.search.SearchDtos.ChoiceAction;
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
import java.util.Optional;
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

  /**
   * What the UI renders above the results when the viewer has no city (spec §4.11). Not an error:
   * the results below it are real, and once a second city exists this sentence becomes the entry
   * point to a city selector rather than a link to the profile.
   */
  static final String CITY_PROMPT =
      "We don't know which city you're in. Set your location on your profile so we can show homes near you.";

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
   *
   * <p>{@code exhausted} is the ladder's tier 4 (spec §4.4): every tier has been walked and the
   * page is still short of {@code minResults}, so there is genuinely nothing more to add. That is
   * what earns the summary's terminus line; a full page never says it.
   */
  private record Homes(
      List<AiResult> results,
      boolean includesNearby,
      double radiusKm,
      int exactCount,
      String rescueSummary,
      boolean exhausted) {}

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

  /**
   * @param scope the city the viewer's places are resolved inside (spec §4.11), which is part of
   *     what a query means: "MG Road" is a different place in Mumbai and in Bangalore, so it is
   *     part of the cache key too rather than letting two viewers in different cities share an
   *     extraction.
   */
  public IntentLlm.Extraction extractIntent(
      String query, SearchIntent prior, UUID userId, String anonKey, CityScope scope) {
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
      SearchIntent resolved = resolveLocalities(heuristic, scope);
      SearchIntent carried =
          resolved.toBuilder().confidence(prior == null ? null : prior.confidence()).build();
      return new IntentLlm.Extraction(carried, IntentLlm.Mode.NONE);
    }

    // 2) cache
    String cacheKey = cacheKey(query, prior, scope);
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
      extracted = new IntentLlm.Extraction(keywordParser.parse(query, scope), IntentLlm.Mode.NONE);
      success = false;
    }
    IntentLlm.Extraction resolved =
        new IntentLlm.Extraction(
            withConfidence(resolveLocalities(extracted.intent(), scope), query, prior, localityResolver),
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

  /**
   * Name → id binding through the full resolution ladder, confined to {@code scope}; names no layer
   * can place are surfaced and logged for the eval report. This is where the ladder built by tasks
   * 5 and 6 actually runs in a production search — nothing else calls it.
   */
  private SearchIntent resolveLocalities(SearchIntent intent, CityScope scope) {
    SearchIntent resolved = IntentLocalities.resolve(intent, localityResolver, scope);
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

  /**
   * There is deliberately no overload that defaults the scope. A caller that does not state which
   * city it is searching in is a caller that has not thought about §4.11, and a convenience
   * overload would let one compile — {@code CityScope.unset()} is cheap to type and says what it
   * means.
   *
   * @param escalated true only for the one re-run that follows an explicit "raise my budget" click
   *     (spec §4.7): the fallback ladder's ring becomes {@code escalationRadiusKm} rather than
   *     {@code nearbyRadiusKm} for this search alone. The automatic path — a page topped up with
   *     nearby or over-budget rows the user never asked for — always passes {@code false}; only
   *     {@link AiSearchController#apply} sets it, and only when the posted intent's own {@code
   *     budgetMax} is higher than the session's prior one. Viewing or scoring an auto-shown row is
   *     not the click that does this.
   * @param scope the viewer's city (spec §4.11), reported back on the response so the UI never has
   *     to infer it. An {@code UNSET} scope is a state, not a Mumbai default: the page is real and
   *     carries a prompt saying we don't know where the viewer is.
   */
  @Transactional(readOnly = true)
  public SearchDtos.AiSearchResponse search(
      SearchIntent intent,
      UUID viewerId,
      String anonKey,
      UUID sessionId,
      String note,
      boolean escalated,
      CityScope scope) {
    String intentHash = EmbeddingTextComposer.sha256(intentJson(intent));
    SearchTarget target = intent.targetOrDefault();
    CityScope citySearchScope = scope == null ? CityScope.unset() : scope;
    Placement placement = placementOf(intent);

    Homes homes =
        target == SearchTarget.FLATMATES
            ? new Homes(List.of(), false, 0.0, 0, null, false)
            : searchHomes(intent, intentHash, viewerId, anonKey, escalated, placement);
    List<AiResult> flatmates =
        target == SearchTarget.PROPERTIES ? List.of() : searchFlatmates(intent, intentHash, viewerId, anonKey);

    List<Relaxer> relaxers = List.of();
    if (homes.results().isEmpty() && target != SearchTarget.FLATMATES) {
      relaxers = computeRelaxers(intent);
    }

    // Offered alongside the results, whatever their number — a thin-but-nonempty page still
    // deserves "Or: Kandivali has 3 from ₹17,000", not just a fully empty one. budgetChoice()
    // itself is the gate: it comes back empty whenever the named place already has something
    // in budget, or raising the budget would open no doors.
    List<Choice> choices =
        target == SearchTarget.FLATMATES
            ? List.of()
            : budgetChoice(intent, placement).map(List::of).orElse(List.of());

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

    // A flatmate-only search has no homes to tier, so it is anchored nowhere as far as the summary
    // is concerned — otherwise every such page would be headlined "No listings in Kandivali".
    boolean framed = target != SearchTarget.FLATMATES;
    SearchDtos.ResultSummary resultSummary =
        summarize(
            framed ? anchorNameOf(placement) : null,
            framed && intent.unresolvedLocations() != null ? List.copyOf(intent.unresolvedLocations()) : List.of(),
            citySearchScope,
            intent,
            homes.exactCount(),
            countOf(homes.results(), RescueLadder.SearchTier.NEARBY),
            countOf(homes.results(), RescueLadder.SearchTier.OVER_BUDGET),
            homes.exhausted());

    return new SearchDtos.AiSearchResponse(
        sessionId,
        intent,
        explanationService.usesLlm() ? explanationService.providerName() : "mock",
        homes.results(),
        flatmates,
        relaxers,
        finalNote,
        resultSummary,
        choices,
        new SearchDtos.CitySearch(
            citySearchScope.city(),
            citySearchScope.source(),
            citySearchScope.isSet() ? null : CITY_PROMPT));
  }

  private static int countOf(List<AiResult> results, RescueLadder.SearchTier tier) {
    return (int) results.stream().filter(r -> r.tier() == tier).count();
  }

  /**
   * The framing above the results (spec §4.6, §4.8). Unanchored splits in two, and the split is the
   * point of carrying {@code unplacedNames}: a name nothing could place is told to the user — "We
   * couldn't place 'Ulwe'" — whereas a query that named no place at all gets no framing whatever,
   * because saying anything about "here" would be inventing a place. Neither has an anchor, so
   * neither gets a terminus: there is nothing to be "near".
   */
  private SearchDtos.ResultSummary summarize(
      String anchorName,
      List<String> unplacedNames,
      CityScope scope,
      SearchIntent intent,
      int exact,
      int nearby,
      int overBudget,
      boolean exhausted) {
    if (anchorName == null) {
      String unplacedHeadline = unplacedNames.isEmpty() ? null : couldNotPlace(unplacedNames, scope);
      return new SearchDtos.ResultSummary(
          null, unplacedNames, exact, nearby, overBudget, unplacedHeadline, null);
    }
    String budget = intent.budgetMax() == null ? null : "₹%,d".formatted(intent.budgetMax());
    String headline =
        exact == 0
            ? "No listings in %s%s.".formatted(anchorName, budget == null ? "" : " under " + budget)
            : exact < 3
                ? "Only %d listing%s in %s%s."
                    .formatted(exact, exact == 1 ? "" : "s", anchorName, budget == null ? "" : " under " + budget)
                : null;
    String terminus =
        exhausted && budget != null
            ? "No more listings within %s near %s.".formatted(budget, anchorName)
            : null;
    return new SearchDtos.ResultSummary(
        anchorName, unplacedNames, exact, nearby, overBudget, headline, terminus);
  }

  /**
   * The §4.8 headline for a name nothing could place. It names the city only when we know it: an
   * {@code UNSET} viewer is already being told we don't know where they are ({@code citySearch}),
   * and claiming "across Mumbai" to them would be the silent default this workstream exists to
   * remove.
   */
  private static String couldNotPlace(List<String> names, CityScope scope) {
    List<String> quoted = names.stream().map("\"%s\""::formatted).toList();
    String listed =
        quoted.size() == 1
            ? quoted.get(0)
            : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.get(quoted.size() - 1);
    return "We couldn't place %s. Showing results %s."
        .formatted(listed, scope.isSet() ? "across " + scope.city() : "from every area we cover");
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

  private Homes searchHomes(
      SearchIntent intent,
      String intentHash,
      UUID viewerId,
      String anonKey,
      boolean escalated,
      Placement placement) {
    // Both default to 5.0 km but are configured separately on purpose (spec §4.7): the automatic
    // path never sees escalated=true, so a page the user never asked to widen stays within
    // nearbyRadiusKm, and only the re-run after an explicit "raise my budget" click reaches out to
    // escalationRadiusKm instead.
    double ringRadiusKm =
        escalated ? props.getSearch().getEscalationRadiusKm() : props.getSearch().getNearbyRadiusKm();
    List<RescueLadder.Tier> tiers =
        RescueLadder.tiers(intent, placement, props.getSearch(), ringRadiusKm);
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
    // Tier 4 (spec §4.4): every tier has been walked and the page is still short. Read off the
    // whole union rather than the twenty rows shown, so a page truncated by RESULT_LIMIT — which
    // has plenty more to offer — never claims there is nothing left.
    boolean exhausted = byId.size() < props.getSearch().getMinResults();

    if (byId.isEmpty()) {
      return new Homes(List.of(), false, 0.0, 0, null, exhausted);
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
        Double distanceKm,
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
      Double distanceKm = null;
      if (c != null) {
        if (anchor == null && !preferred.isEmpty()) {
          anchor = nearestOf(preferred, c);
        }
        if (anchor != null) {
          commuteMinutes =
              c.lat() != null
                  ? commuteEstimator.minutesFromPoint(c.lat(), c.lng(), anchor)
                  : commuteEstimator.minutesBetween(c.localityId(), anchor);
          Double km =
              c.lat() != null
                  ? commuteEstimator.kmFromPoint(c.lat(), c.lng(), anchor)
                  : commuteEstimator.kmBetween(c.localityId(), anchor);
          // one decimal: the figure is a straight line between centroids, and "3.14159 km" would
          // claim a precision the estimate does not have
          distanceKm = km == null ? null : Math.round(km * 10) / 10.0;
        }
      }
      // anchor is a real, resolved locality id whenever it is non-null (see commuteAnchor/nearestOf
      // above), but nameOf can still come back null if the resolver's cache is stale relative to
      // the id it was handed. Null here means exactly "we cannot name this place", never a wrong
      // city standing in for one: it is what the API reports as the row's anchorName, and it is
      // what anchorLabel() turns into the honest "your area" placeholder for prose. The
      // commuteIntent branch needs no different treatment: commuteTo.place is a plain nullable
      // String on the wire (SearchIntent.CommuteTo), and /apply replays a client-submitted intent
      // straight through this pipeline with no IntentLocalities.resolve pass — a body carrying only
      // commuteTo.localityId (no place) must not render the literal "null" into "~N min to null".
      String anchorName =
          commuteIntent
              ? intent.commuteTo().place()
              : anchor == null ? null : localityResolver.nameOf(anchor);
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
              anchorLabel(anchorName),
              commuteIntent,
              decayMinutes,
              inPreferred);
      rows.add(
          new Row(
              l,
              c,
              MatchScorer.scoreListing(intent, candidate),
              commuteMinutes,
              distanceKm,
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
    double nearbyRadiusKm = rescue.widerRingRadiusKm() == null ? ringRadiusKm : rescue.widerRingRadiusKm();

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
    // `preferred` is ungated on purpose — a place the reader only inferred still ranks rows by
    // proximity to it (that happens in the scorer). But it anchors nothing: no locality reached the
    // WHERE clause, so the page is citywide and there is no ring it was searched around. The row's
    // distance-shaped components say so by being absent, rather than labelling a citywide result
    // "3.4 km from Kandivali" under a heading that calls it an exact match.
    boolean anchored = placement.placed();

    List<AiResult> out = new ArrayList<>();
    for (Row r : top) {
      Explanation e = explanations.get(r.listing().getId());
      // a home inside a requested locality needs no distance label; commute intents label everything
      String label =
          r.commute() == null || (!commuteIntent && r.inPreferred())
              ? null
              : "~%d min %s %s (estimate)"
                  .formatted(r.commute(), commuteIntent ? "to" : "from", anchorLabel(r.anchorName()));
      boolean nearMiss = r.tier() != RescueLadder.SearchTier.EXACT;
      String nearMissReason =
          nearMiss
              ? nearMissReason(
                  r.tier(),
                  r.listing().getRentMonthly(),
                  r.commute(),
                  anchorLabel(r.anchorName()),
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
              nearMissReason,
              r.tier(),
              anchored ? r.distanceKm() : null,
              anchored ? r.commute() : null,
              anchored ? r.anchorName() : null));
    }
    return new Homes(out, includesNearby, nearbyRadiusKm, exactCount, rescueSummary, exhausted);
  }

  /**
   * What to call the anchor in prose when we cannot name it. Null means "we don't know which place
   * this is", which is honest on the wire but unreadable in a sentence — "your area" is the same
   * placeholder the page has always used, and never a wrong city standing in for a real one.
   */
  private static String anchorLabel(String anchorName) {
    return Objects.requireNonNullElse(anchorName, "your area");
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
              null,
              // a flatmate is not tiered and is not measured from a placement: the fallback ladder
              // is a homes concern, so stating a tier or a distance here would be inventing one
              null,
              null,
              null,
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

  /**
   * The one compromise worth offering when a place has nothing in budget: what the cheapest
   * listing there actually costs, and how many open up at that price. Returns empty when raising
   * the budget changes nothing — either nothing is stated to raise, there is nowhere to anchor the
   * claim, the cheapest option already fits (so there is nothing to open), or the rounded offer
   * would open no doors — because an offer that opens no doors is noise.
   *
   * <p>This never writes to {@code intent}; it only ever reads it and hands back a number. The
   * stated budget moves only when the caller posts this value to {@code /api/v1/ai/apply} — see
   * {@link AiSearchController#apply}.
   */
  Optional<Choice> budgetChoice(SearchIntent intent, Placement placement) {
    if (intent.budgetMax() == null || !placement.placed()) {
      return Optional.empty();
    }
    SearchIntent uncapped = intent.toBuilder().budgetMax(null).build();
    Integer cheapest = cheapestRentFor(uncapped);
    if (cheapest == null || cheapest <= intent.budgetMax()) {
      return Optional.empty();
    }
    int suggested = (int) (Math.ceil(cheapest / 500.0) * 500);
    long count = countFor(intent.toBuilder().budgetMax(suggested).build());
    if (count == 0) {
      return Optional.empty();
    }
    String place = placementName(placement);
    return Optional.of(
        new Choice(
            "%s has %d from ₹%,d".formatted(place, count, suggested),
            ChoiceAction.RAISE_BUDGET,
            suggested,
            count));
  }

  /**
   * The place a search is anchored on, by name — the first of the placement's localities — or null
   * when there is nothing to name: no anchor at all, or an id the resolver cannot name. Null is the
   * whole summary's switch: no anchor means no headline and no terminus (spec §4.8), because a
   * query that named no locality is simply citywide and has nothing to be framed against.
   */
  private String anchorNameOf(Placement placement) {
    if (!placement.placed() || placement.localityIds().isEmpty()) {
      return null;
    }
    return localityResolver.nameOf(placement.localityIds().get(0));
  }

  /**
   * The same anchor written for prose, so a choice chip and a result row never disagree about what
   * to call the same place — both fall back to "your area" through {@link #anchorLabel}.
   */
  private String placementName(Placement placement) {
    return anchorLabel(anchorNameOf(placement));
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

  /**
   * The scope is part of the key, not decoration: the same words mean different places in different
   * cities ("MG Road", "Indiranagar"), so two viewers scoped differently must never share a cached
   * extraction. {@code UNSET} is its own key for the same reason — it resolves across every city.
   */
  private static String cacheKey(String query, SearchIntent prior, CityScope scope) {
    String normalized = query.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    String priorPart = prior == null ? "" : String.valueOf(prior.hashCode());
    String scopePart = scope == null || !scope.isSet() ? "unset" : scope.city().toLowerCase(Locale.ROOT);
    return EmbeddingTextComposer.sha256(normalized + "|" + priorPart + "|" + scopePart);
  }
}
