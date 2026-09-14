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
  private final Map<String, List<UUID>> byPhrase = new LinkedHashMap<>();
  private final Map<UUID, String> nameById = new LinkedHashMap<>();
  private final List<Locality> loaded = new ArrayList<>();

  @PostConstruct
  void load() {
    byPhrase.clear();
    nameById.clear();
    loaded.clear();
    for (Locality l : localities.findAll()) {
      loaded.add(l);
      nameById.put(l.getId(), l.getName());
      index(l.getName(), l.getId());
      for (String alias : l.getAliases()) {
        index(alias, l.getId());
      }
    }
  }

  /** Re-reads the gazetteer; the seed runner calls this after inserting localities. */
  public void reload() {
    load();
  }

  private void index(String phrase, UUID id) {
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

  /** Every locality mentioned in the text, non-overlapping, in text order. */
  public List<Match> scan(String text) {
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
        List<UUID> ids = byPhrase.get(phrase);
        if (ids != null) {
          out.add(new Match(List.copyOf(ids), canonical(ids), i, i + w, phrase, EXACT));
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
        Fuzzy best = bestFuzzy(phrase, w);
        if (best != null) {
          out.add(new Match(best.ids(), canonical(best.ids()), i, i + w, phrase, best.similarity()));
          mark(taken, i, i + w);
        }
      }
    }

    out.sort(Comparator.comparingInt(Match::tokenStart));
    return out;
  }

  /** Whole-string resolution of a name the LLM or a chip supplied; never guesses by substring. */
  public Optional<Match> resolve(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String key = normalize(name);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    List<UUID> exact = byPhrase.get(key);
    if (exact != null) {
      return Optional.of(new Match(List.copyOf(exact), canonical(exact), 0, 0, key, EXACT));
    }
    if (key.length() < MIN_FUZZY_LENGTH) {
      return Optional.empty();
    }
    Fuzzy best = bestFuzzy(key, key.split(" ").length);
    if (best == null) {
      return Optional.empty();
    }
    return Optional.of(new Match(best.ids(), canonical(best.ids()), 0, 0, key, best.similarity()));
  }

  public String nameOf(UUID id) {
    return nameById.getOrDefault(id, "Mumbai");
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

  /** Best phrase with the same word count whose trigram similarity clears the threshold. */
  private Fuzzy bestFuzzy(String phrase, int words) {
    Fuzzy best = null;
    for (Map.Entry<String, List<UUID>> e : byPhrase.entrySet()) {
      if (e.getKey().split(" ").length != words) {
        continue;
      }
      double sim = Trigrams.similarity(phrase, e.getKey());
      if (sim >= FUZZY_THRESHOLD && (best == null || sim > best.similarity())) {
        best = new Fuzzy(List.copyOf(e.getValue()), sim);
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
