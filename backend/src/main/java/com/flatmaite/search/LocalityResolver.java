package com.flatmaite.search;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Locality name → ids, in layers. Layer 1 is an exact gazetteer over word-boundary token windows
 * (longest match wins, so "bandra kurla complex" is BKC and never also Bandra + Kurla). Layer 2 is
 * trigram similarity for typos. Layer 3 — paraphrase and landmarks — is the LLM, which receives
 * {@link #vocabulary()} and emits canonical names that hit layer 1. Every match carries its token
 * position so callers can read the words around it (negation, commute cues).
 *
 * <p>An alias may name several localities ("andheri" → Andheri East and Andheri West); such a match
 * carries every id and the parser expands it into one location per id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalityResolver {

  public static final double EXACT = 1.0;
  public static final double FUZZY_THRESHOLD = 0.55;
  /** Below this a match is a guess: the detector does not count it as an anchor. */
  public static final double CONFIDENT = 0.75;
  public static final int MIN_FUZZY_LENGTH = 5;
  public static final int MAX_WINDOW = 3;

  /** A located mention. {@code tokenEnd} is exclusive. */
  public record Match(
      List<UUID> localityIds,
      String canonicalName,
      int tokenStart,
      int tokenEnd,
      String matchedText,
      double confidence) {}

  private final LocalityRepository localities;
  // volatile + rebuild-then-swap in load(): a concurrent scan/resolve reads one consistent
  // generation of the gazetteer rather than racing a clear()-then-repopulate in place.
  private volatile Map<String, List<UUID>> byPhrase = new LinkedHashMap<>();
  private volatile Map<UUID, String> nameById = new LinkedHashMap<>();
  private volatile Map<UUID, String> cityById = new LinkedHashMap<>();
  private volatile Map<UUID, Point> pointById = new LinkedHashMap<>();
  private volatile List<Locality> loaded = new ArrayList<>();
  private final AtomicLong version = new AtomicLong();

  private record Point(double lat, double lng) {}

  @PostConstruct
  void load() {
    Map<String, List<UUID>> newByPhrase = new LinkedHashMap<>();
    Map<UUID, String> newNameById = new LinkedHashMap<>();
    Map<UUID, String> newCityById = new LinkedHashMap<>();
    Map<UUID, Point> newPointById = new LinkedHashMap<>();
    List<Locality> newLoaded = new ArrayList<>();
    for (Locality l : localities.findAll()) {
      newLoaded.add(l);
      newNameById.put(l.getId(), l.getName());
      newCityById.put(l.getId(), l.getCity());
      newPointById.put(l.getId(), new Point(l.getLat(), l.getLng()));
      index(newByPhrase, l.getName(), l.getId());
      for (String alias : l.getAliases()) {
        index(newByPhrase, alias, l.getId());
      }
    }
    byPhrase = newByPhrase;
    nameById = newNameById;
    cityById = newCityById;
    pointById = newPointById;
    loaded = newLoaded;
    version.incrementAndGet();
  }

  /** Re-reads the gazetteer; the seed runner calls this after inserting localities. */
  public void reload() {
    load();
  }

  /**
   * Monotonically increasing, bumped at the end of every {@link #load()}/{@link #reload()} — lets
   * a derived cache (the intent prompt's vocabulary block) detect that it is stale and rebuild.
   */
  public long version() {
    return version.get();
  }

  private static void index(Map<String, List<UUID>> byPhrase, String phrase, UUID id) {
    String key = normalize(phrase);
    if (key.isEmpty()) {
      return;
    }
    List<UUID> ids = byPhrase.computeIfAbsent(key, k -> new ArrayList<>());
    if (!ids.contains(id)) {
      if (!ids.isEmpty()) {
        log.debug("Locality phrase '{}' is shared by {} localities", key, ids.size() + 1);
      }
      ids.add(id);
    }
  }

  static String normalize(String phrase) {
    return Tokens.of(phrase).stream().map(Tokens.Token::text).collect(Collectors.joining(" "));
  }

  /** Every locality mentioned in the text, non-overlapping, in text order — unscoped: every city. */
  public List<Match> scan(String text) {
    return scan(text, CityScope.unset());
  }

  /**
   * Every locality mentioned in the text, non-overlapping, in text order, confined to {@code
   * scope} when it is set. Scoping is applied to the exact/alias layer and, critically, inside
   * fuzzy matching before similarity is scored — a wrong-city candidate can win on spelling alone
   * if it is filtered out only afterward.
   */
  public List<Match> scan(String text, CityScope scope) {
    List<Tokens.Token> tokens = Tokens.of(text);
    boolean[] taken = new boolean[tokens.size()];
    List<Match> out = new ArrayList<>();

    // layer 1: exact, longest window first
    for (int w = MAX_WINDOW; w >= 1; w--) {
      for (int i = 0; i + w <= tokens.size(); i++) {
        if (anyTaken(taken, i, i + w)) {
          continue;
        }
        String phrase = Tokens.phrase(tokens, i, i + w);
        List<UUID> ids = inScope(byPhrase.get(phrase), scope);
        if (!ids.isEmpty()) {
          out.add(new Match(ids, canonical(ids), i, i + w, phrase, EXACT));
          mark(taken, i, i + w);
        }
      }
    }

    // layer 2: fuzzy, two-token windows then single tokens, on what is left
    for (int w = 2; w >= 1; w--) {
      for (int i = 0; i + w <= tokens.size(); i++) {
        if (anyTaken(taken, i, i + w)) {
          continue;
        }
        String phrase = Tokens.phrase(tokens, i, i + w);
        if (phrase.length() < MIN_FUZZY_LENGTH) {
          continue;
        }
        Fuzzy best = bestFuzzy(phrase, w, scope);
        if (best != null) {
          out.add(new Match(best.ids(), canonical(best.ids()), i, i + w, phrase, best.similarity()));
          mark(taken, i, i + w);
        }
      }
    }

    out.sort(Comparator.comparingInt(Match::tokenStart));
    return out;
  }

  /**
   * Whole-string resolution of a name the LLM or a chip supplied; never guesses by substring.
   * Unscoped: reaches every seeded city.
   */
  public Optional<Match> resolve(String name) {
    return resolveMatch(name, CityScope.unset());
  }

  /**
   * Whole-string resolution confined to {@code scope} when it is set. This is the gazetteer step
   * of the resolution ladder (spec §4.3): a hit is always {@link Placement.Source#GAZETTEER}, and
   * a name that only matches outside {@code scope} — exactly or by fuzzy similarity — comes back
   * {@link Placement.Source#NONE} rather than crossing the city boundary.
   */
  public Placement resolve(String name, CityScope scope) {
    return resolveMatch(name, scope).map(this::toPlacement).orElseGet(Placement::none);
  }

  private Optional<Match> resolveMatch(String name, CityScope scope) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String key = normalize(name);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    List<UUID> exact = inScope(byPhrase.get(key), scope);
    if (!exact.isEmpty()) {
      return Optional.of(new Match(exact, canonical(exact), 0, 0, key, EXACT));
    }
    if (key.length() < MIN_FUZZY_LENGTH) {
      return Optional.empty();
    }
    Fuzzy best = bestFuzzy(key, key.split(" ").length, scope);
    if (best == null) {
      return Optional.empty();
    }
    return Optional.of(new Match(best.ids(), canonical(best.ids()), 0, 0, key, best.similarity()));
  }

  private Placement toPlacement(Match m) {
    Point centroid = centroidOf(m.localityIds());
    Double lat = centroid == null ? null : centroid.lat();
    Double lng = centroid == null ? null : centroid.lng();
    return new Placement(m.localityIds(), lat, lng, Placement.Source.GAZETTEER, m.confidence());
  }

  /** Average of the matched localities' points; a single id is simply that locality's point. */
  private Point centroidOf(List<UUID> ids) {
    double lat = 0;
    double lng = 0;
    int n = 0;
    for (UUID id : ids) {
      Point p = pointById.get(id);
      if (p != null) {
        lat += p.lat();
        lng += p.lng();
        n++;
      }
    }
    return n == 0 ? null : new Point(lat / n, lng / n);
  }

  /**
   * Filters candidate ids to {@code scope} before any similarity scoring can see them — an UNSET
   * scope reaches every city (today that is only Mumbai, §4.11); a set scope keeps only ids whose
   * locality belongs to it. Always returns an immutable list, never the caller's own reference.
   */
  private List<UUID> inScope(List<UUID> ids, CityScope scope) {
    if (ids == null) {
      return List.of();
    }
    if (!scope.isSet()) {
      return List.copyOf(ids);
    }
    return ids.stream().filter(id -> scope.city().equalsIgnoreCase(cityById.get(id))).toList();
  }

  public String nameOf(UUID id) {
    return nameById.getOrDefault(id, "Mumbai");
  }

  /** The city a locality belongs to, or null when the id is unknown — callers fall back themselves. */
  public String cityOf(UUID id) {
    return cityById.get(id);
  }

  /** "Name (alias, alias)" per locality — the controlled vocabulary handed to the intent prompt. */
  public List<String> vocabulary() {
    List<String> out = new ArrayList<>();
    for (Locality l : loaded) {
      String[] aliases = l.getAliases();
      out.add(aliases.length == 0 ? l.getName() : l.getName() + " (" + String.join(", ", aliases) + ")");
    }
    return out;
  }

  private record Fuzzy(List<UUID> ids, double similarity) {}

  /**
   * Best phrase with the same word count whose trigram similarity clears the threshold, among
   * candidates already narrowed to {@code scope}. Scoping happens before scoring — a phrase whose
   * every id is filtered out never gets a similarity computed, so a wrong-city near-miss can never
   * outscore an in-scope one (or resolve at all, when nothing in scope is close).
   */
  private Fuzzy bestFuzzy(String phrase, int words, CityScope scope) {
    Fuzzy best = null;
    for (Map.Entry<String, List<UUID>> e : byPhrase.entrySet()) {
      if (e.getKey().split(" ").length != words) {
        continue;
      }
      List<UUID> ids = inScope(e.getValue(), scope);
      if (ids.isEmpty()) {
        continue;
      }
      double sim = Trigrams.similarity(phrase, e.getKey());
      if (sim >= FUZZY_THRESHOLD && (best == null || sim > best.similarity())) {
        best = new Fuzzy(ids, sim);
      }
    }
    return best;
  }

  private String canonical(List<UUID> ids) {
    return ids.stream().map(nameById::get).collect(Collectors.joining(" / "));
  }

  private static boolean anyTaken(boolean[] taken, int from, int to) {
    for (int i = from; i < to; i++) {
      if (taken[i]) {
        return true;
      }
    }
    return false;
  }

  private static void mark(boolean[] taken, int from, int to) {
    for (int i = from; i < to; i++) {
      taken[i] = true;
    }
  }
}
