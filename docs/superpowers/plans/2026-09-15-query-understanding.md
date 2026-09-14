# Query Understanding (WS2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make locality understanding correct and position-aware (exact → fuzzy → LLM-canonicalised), admit nearby areas ranked by distance, fix the keyword parser's budget/verified handling, make the controller the single new-vs-refine arbiter (with the LLM's opinion as a tie-breaker), and clean up the prompts.

**Architecture:** A shared `Tokens` tokenizer feeds a rewritten in-memory `LocalityResolver` (word-boundary n-gram gazetteer with longest-match and trigram fuzzy fallback, returning positioned `Match`es), which `LocationMentions` classifies into home / exclude / commute by cue words. `SearchIntent` gains `excludeLocations` and `unresolvedLocations`; `HybridRetriever` widens a named locality to its `nearby-radius-minutes` neighbourhood and `MatchScorer` decays the location score with distance to the nearest requested locality. `NewQueryDetector.decide()` returns NEW / REFINE / AMBIGUOUS and the controller consults the LLM's `mode` only in the ambiguous zone. Prompts receive the locality vocabulary and few-shot examples; the refine prompt merges only; the prior intent moves to the user role.

**Tech Stack:** Java 17 · Spring Boot 3.5 · Spring AI 1.1.8 (`ChatClient`, `BeanOutputConverter`) · PostgreSQL 16 + pgvector · JUnit 5 + AssertJ + Mockito · Testcontainers · Next.js 15 / TypeScript (type mirror + two chips only)

**Spec:** `docs/superpowers/specs/2026-09-15-query-understanding-design.md`

## Global Constraints

- Java 17; Maven wrapper — run every backend command from `backend/`. Frontend commands from `frontend/`.
- **No schema change, no Flyway migration.** `SearchIntent` changes are additive only (`excludeLocations`, `unresolvedLocations`, constant `DEFAULT_COMMUTE_MINUTES = 30`).
- WS1 constants untouched: `RankFusion.K = 60`, `VECTOR_LIMIT = 100`, `FTS_LIMIT = 50`, `MAX_LEXICAL_TOKENS = 24`, `MAX_FREE_TEXT_CHARS = 600`, relevance weights 0.15 / 0.20.
- Resolver constants: `MAX_WINDOW = 3` tokens, `FUZZY_THRESHOLD = 0.55` (trigram Jaccard), `CONFIDENT = 0.75` (anchor threshold), `MIN_FUZZY_LENGTH = 5`. Exact matches have confidence `1.0`.
- Cue words (verbatim): exclude `{not, no, except, excluding, avoid, nahi}` within the 2 tokens before a match (the token immediately before may be a preposition), plus two-token phrases `other than`, `anywhere but`, `apart from`, and `nahi`/`mat` immediately after; commute `{near, nearby, close, around, next, within, work, working, office, commute, commuting}` within the 4 tokens before.
- Distance: `flatmaite.search.nearby-radius-minutes` default **25**; `SearchIntent.DEFAULT_COMMUTE_MINUTES = 30` replaces every `45` commute default; location score = `1.0` in a requested locality, else `max(0.3, 1 − minutes / (2 × radius))`. Detail strings: `"In %s — one of your preferred areas"`, `"~%d min from %s (estimate)"` (home anchor), `"~%d min to %s (estimate)"` (commute anchor), `"Outside your preferred areas"`. Response note: `"Also showing nearby areas within ~%d min."`.
- Seed: `LISTING_COUNT = 80` (29 PRIVATE_ROOM, 16 SHARED_ROOM, 16 ENTIRE_APARTMENT, 13 LOOKING_FOR_FLATMATE, 6 REPLACEMENT), `FLATMATE_COUNT = 35`, `Random(42)`; locality table exactly as spec §4.3.
- Detector cue lists (verbatim): fresh `forget that|forget it|start over|new search|scrap that|from scratch`; refinement `make it|instead|also|actually|same but|but in|rather|change it|change the|only|cheaper|closer|nearer`. Rule order: fresh cue → NEW; anchors ≥ 3 → NEW; refinement cue → REFINE; anchors ≥ 2 and housing noun → NEW; anchors == 0 → REFINE; else AMBIGUOUS.
- Tests needing Docker (Testcontainers): `LocationWideningIntegrationTest`, `SearchPipelineIntegrationTest`, `HybridRetrieverIntegrationTest`, `AuthFlowIntegrationTest`. All others are pure.
- Commit messages: short imperative subject in the repo's style, ending with the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Branch: `query-understanding` (already created; the spec is its first commit).

**Dev-database note (not a task):** the seed renames locality "Andheri" into "Andheri East"/"Andheri West" with new deterministic ids. Fresh Testcontainers databases are unaffected; a developer's existing local database keeps an orphan "Andheri" row — `docker compose down -v && docker compose up -d` then re-seed to clean it.

---

### Task 1: `Tokens` and `NumberWords` — shared tokenizer and spelled-out amounts

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/Tokens.java`
- Create: `backend/src/main/java/com/flatmaite/search/NumberWords.java`
- Test: `backend/src/test/java/com/flatmaite/search/TokensTest.java`
- Test: `backend/src/test/java/com/flatmaite/search/NumberWordsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Tokens.Token(String text, int start, int end)`; `static List<Tokens.Token> Tokens.of(String text)` (lower-cased words matching `[\p{L}\p{N}]+`, with character spans into the lower-cased text; `null` → empty list); `static String Tokens.phrase(List<Token> tokens, int from, int to)` (joins `tokens[from, to)` with single spaces). `static OptionalInt NumberWords.parse(String phrase)` — spelled-out or mixed amounts ("twenty five thousand" → 25000, "one and a half lakh" → 150000, "1.5 lakh" → 150000, "thirty" → 30); empty for anything that is not a number phrase. `static final Pattern NumberWords.NUMBER_RUN` — a regex matching a run of number words (used by Task 5 to find spelled-out amounts inside a query).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/flatmaite/search/TokensTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokensTest {

  @Test
  void lowercasesAndSplitsOnNonLettersOrDigits() {
    List<Tokens.Token> t = Tokens.of("Room in Andheri-West, 25k!");

    assertThat(t).extracting(Tokens.Token::text)
        .containsExactly("room", "in", "andheri", "west", "25k");
  }

  @Test
  void spansIndexTheLowercasedText() {
    List<Tokens.Token> t = Tokens.of("BKC room");

    assertThat(t.get(0).start()).isEqualTo(0);
    assertThat(t.get(0).end()).isEqualTo(3);
    assertThat(t.get(1).start()).isEqualTo(4);
    assertThat(t.get(1).end()).isEqualTo(8);
  }

  @Test
  void keepsUnicodeLetters() {
    assertThat(Tokens.of("पवई room")).extracting(Tokens.Token::text).containsExactly("पवई", "room");
  }

  @Test
  void nullAndBlank_yieldNoTokens() {
    assertThat(Tokens.of(null)).isEmpty();
    assertThat(Tokens.of("  ,, ")).isEmpty();
  }

  @Test
  void phraseJoinsARangeWithSingleSpaces() {
    List<Tokens.Token> t = Tokens.of("near bandra kurla complex please");

    assertThat(Tokens.phrase(t, 1, 4)).isEqualTo("bandra kurla complex");
  }
}
```

Create `backend/src/test/java/com/flatmaite/search/NumberWordsTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NumberWordsTest {

  @Test
  void spelledOutThousands() {
    assertThat(NumberWords.parse("twenty five thousand")).hasValue(25000);
    assertThat(NumberWords.parse("thirty thousand")).hasValue(30000);
    assertThat(NumberWords.parse("fifteen thousand")).hasValue(15000);
  }

  @Test
  void lakhsIncludingFractions() {
    assertThat(NumberWords.parse("one lakh")).hasValue(100000);
    assertThat(NumberWords.parse("one and a half lakh")).hasValue(150000);
    assertThat(NumberWords.parse("1.5 lakh")).hasValue(150000);
    assertThat(NumberWords.parse("two lakhs")).hasValue(200000);
  }

  @Test
  void smallNumbersAndHundreds() {
    assertThat(NumberWords.parse("thirty")).hasValue(30);
    assertThat(NumberWords.parse("five hundred")).hasValue(500);
    assertThat(NumberWords.parse("twelve")).hasValue(12);
  }

  @Test
  void ignoresFillerWordsAndCurrency() {
    assertThat(NumberWords.parse("rs twenty thousand rupees")).hasValue(20000);
  }

  @Test
  void nonNumbers_areEmpty() {
    assertThat(NumberWords.parse("hello world")).isEmpty();
    assertThat(NumberWords.parse("")).isEmpty();
    assertThat(NumberWords.parse(null)).isEmpty();
    assertThat(NumberWords.parse("and a")).isEmpty();
  }

  @Test
  void numberRun_findsSpelledOutAmountsInsideASentence() {
    var m = NumberWords.NUMBER_RUN.matcher("a room for twenty five thousand in malad");

    assertThat(m.find()).isTrue();
    assertThat(m.group().trim()).isEqualTo("twenty five thousand");
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='TokensTest,NumberWordsTest' 2>&1 | tail -20`
Expected: `COMPILATION ERROR` — `cannot find symbol: class Tokens` / `NumberWords`.

- [ ] **Step 3: Implement `Tokens`**

Create `backend/src/main/java/com/flatmaite/search/Tokens.java`:

```java
package com.flatmaite.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one tokenisation shared by the locality resolver, the keyword parser and the new-query
 * detector: lower-cased runs of letters/digits with their character spans. Sharing it is what lets
 * a resolver match be located relative to the words around it.
 */
public final class Tokens {

  private Tokens() {}

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

  /** One word; {@code start}/{@code end} index the lower-cased text. */
  public record Token(String text, int start, int end) {}

  public static List<Token> of(String text) {
    List<Token> out = new ArrayList<>();
    if (text == null) {
      return out;
    }
    Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (m.find()) {
      out.add(new Token(m.group(), m.start(), m.end()));
    }
    return out;
  }

  /** Joins {@code tokens[from, to)} with single spaces. */
  public static String phrase(List<Token> tokens, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      if (i > from) {
        sb.append(' ');
      }
      sb.append(tokens.get(i).text());
    }
    return sb.toString();
  }
}
```

- [ ] **Step 4: Implement `NumberWords`**

Create `backend/src/main/java/com/flatmaite/search/NumberWords.java`:

```java
package com.flatmaite.search;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spelled-out Indian rupee amounts — "twenty five thousand", "one and a half lakh", "1.5 lakh".
 * The glossary promises the LLM these are understood; the keyword parser has to honour the same
 * promise so mock and live modes agree.
 */
public final class NumberWords {

  private NumberWords() {}

  private static final Map<String, Integer> UNITS =
      Map.ofEntries(
          Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
          Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
          Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
          Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
          Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
          Map.entry("nineteen", 19));

  private static final Map<String, Integer> TENS =
      Map.of("twenty", 20, "thirty", 30, "forty", 40, "fifty", 50, "sixty", 60, "seventy", 70,
          "eighty", 80, "ninety", 90);

  private static final Set<String> FILLER = Set.of("and", "a", "an", "rs", "rupees", "inr");

  private static final String WORD_ALTERNATIVES =
      "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen"
          + "|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
          + "|hundred|thousand|lakhs?|lac|half|and|a|an|\\d+(?:\\.\\d+)?";

  /** A run of number words inside a sentence, e.g. "twenty five thousand". Includes trailing space. */
  public static final Pattern NUMBER_RUN =
      Pattern.compile("(?:\\b(?:" + WORD_ALTERNATIVES + ")\\b\\s*){2,}|\\b\\d+(?:\\.\\d+)?\\s*(?:lakhs?|lac)\\b");

  private static final Pattern NUMERIC = Pattern.compile("\\d+(?:\\.\\d+)?");

  public static OptionalInt parse(String phrase) {
    if (phrase == null || phrase.isBlank()) {
      return OptionalInt.empty();
    }
    double total = 0;
    double current = 0;
    boolean sawNumber = false;
    for (String raw : phrase.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
      String w = raw.replace(",", "");
      if (FILLER.contains(w)) {
        continue;
      }
      if (NUMERIC.matcher(w).matches()) {
        current += Double.parseDouble(w);
        sawNumber = true;
      } else if (UNITS.containsKey(w)) {
        current += UNITS.get(w);
        sawNumber = true;
      } else if (TENS.containsKey(w)) {
        current += TENS.get(w);
        sawNumber = true;
      } else if (w.equals("half")) {
        current += 0.5;
        sawNumber = true;
      } else if (w.equals("hundred")) {
        current = (current == 0 ? 1 : current) * 100;
        sawNumber = true;
      } else if (w.equals("thousand")) {
        total += (current == 0 ? 1 : current) * 1_000;
        current = 0;
        sawNumber = true;
      } else if (w.equals("lakh") || w.equals("lakhs") || w.equals("lac")) {
        total += (current == 0 ? 1 : current) * 100_000;
        current = 0;
        sawNumber = true;
      } else {
        return OptionalInt.empty();
      }
    }
    if (!sawNumber) {
      return OptionalInt.empty();
    }
    return OptionalInt.of((int) Math.round(total + current));
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='TokensTest,NumberWordsTest' 2>&1 | tail -15`
Expected: `TokensTest` 5 tests, `NumberWordsTest` 6 tests, `Failures: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/flatmaite/search/Tokens.java src/main/java/com/flatmaite/search/NumberWords.java src/test/java/com/flatmaite/search/TokensTest.java src/test/java/com/flatmaite/search/NumberWordsTest.java
git commit -m "$(cat <<'EOF'
Add shared tokenizer and spelled-out rupee amount parser

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Layered `LocalityResolver`, `LocationMentions`, and the wider locality seed

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/Trigrams.java`
- Modify: `backend/src/main/java/com/flatmaite/search/LocalityResolver.java` (full rewrite)
- Create: `backend/src/main/java/com/flatmaite/search/LocationMentions.java`
- Modify: `backend/src/main/java/com/flatmaite/seed/SeedRunner.java` — `LISTING_COUNT`, `LOCALITIES`, listing-type distribution
- Modify (compile-only, temporary): `backend/src/main/java/com/flatmaite/search/KeywordIntentParser.java:105-106`, `backend/src/main/java/com/flatmaite/search/NewQueryDetector.java:39`, `backend/src/main/java/com/flatmaite/search/SearchPipeline.java:141,149`, `backend/src/main/java/com/flatmaite/search/HybridRetriever.java:305,315` — adapt to the new `resolve` return type (Tasks 3–7 rewrite these sites properly)
- Test: `TrigramsTest`, `LocalityResolverTest`, `LocationMentionsTest` (new, in `backend/src/test/java/com/flatmaite/search/`)

**Interfaces:**
- Consumes: `Tokens.of`, `Tokens.phrase` (Task 1).
- Produces:
  - `static double Trigrams.similarity(String a, String b)` — pg_trgm-style Jaccard over padded trigrams, 0..1.
  - `LocalityResolver.Match(List<UUID> localityIds, String canonicalName, int tokenStart, int tokenEnd, String matchedText, double confidence)` (`tokenEnd` exclusive).
  - `List<Match> LocalityResolver.scan(String text)` — non-overlapping, ordered by `tokenStart`; `Optional<Match> resolve(String name)`; `String nameOf(UUID)`; `List<String> vocabulary()` — one `"Name (alias, alias)"` line per locality (`"Name"` when no aliases); `void load()` stays package-private and idempotent; constructor `(LocalityRepository)` unchanged; constants `EXACT`, `FUZZY_THRESHOLD`, `CONFIDENT`, `MIN_FUZZY_LENGTH`, `MAX_WINDOW`.
  - `LocationMentions(List<Match> home, List<Match> exclude, Optional<Match> commute)` with `static LocationMentions from(List<Tokens.Token> tokens, List<Match> matches)`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/flatmaite/search/TrigramsTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrigramsTest {

  @Test
  void identicalStrings_scoreOne() {
    assertThat(Trigrams.similarity("powai", "powai")).isEqualTo(1.0);
  }

  @Test
  void insertionTypo_clearsTheThreshold() {
    // "  powaii " vs "  powai ": 5 shared of 8 distinct trigrams
    assertThat(Trigrams.similarity("powaii", "powai")).isGreaterThanOrEqualTo(LocalityResolver.FUZZY_THRESHOLD);
    assertThat(Trigrams.similarity("malaad", "malad")).isGreaterThanOrEqualTo(LocalityResolver.FUZZY_THRESHOLD);
  }

  @Test
  void commonWordNearAPlaceName_staysBelowTheThreshold() {
    // "world" vs "worli" shares 4 of 8 — must not become a Worli filter
    assertThat(Trigrams.similarity("world", "worli")).isLessThan(LocalityResolver.FUZZY_THRESHOLD);
  }

  @Test
  void disjointStrings_scoreZero() {
    assertThat(Trigrams.similarity("abc", "xyz")).isEqualTo(0.0);
    assertThat(Trigrams.similarity("", "powai")).isEqualTo(0.0);
  }
}
```

Create `backend/src/test/java/com/flatmaite/search/LocalityResolverTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LocalityResolverTest {

  private LocalityResolver resolver;

  static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes());
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", "hiranandani"),
                locality("Goregaon", "goregaon east", "goregaon west"),
                locality("Andheri East", "andheri east", "andheri"),
                locality("Andheri West", "andheri west", "andheri"),
                locality("BKC", "bandra kurla complex", "bandra kurla"),
                locality("Bandra", "bandra west"),
                locality("Kurla"),
                locality("Sion"),
                locality("Parel", "parel"),
                locality("Lower Parel", "lower parel"),
                locality("Worli")));
    resolver = new LocalityResolver(repo);
    resolver.load();
  }

  @Test
  void longestMatchWins_soBandraKurlaComplexIsOnlyBkc() {
    List<LocalityResolver.Match> m = resolver.scan("a room near bandra kurla complex please");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("BKC"));
    assertThat(m.get(0).canonicalName()).isEqualTo("BKC");
    assertThat(m.get(0).tokenStart()).isEqualTo(3);
    assertThat(m.get(0).tokenEnd()).isEqualTo(6);
    assertThat(m.get(0).confidence()).isEqualTo(LocalityResolver.EXACT);
  }

  @Test
  void ambiguousAlias_yieldsEveryLocalityItNames() {
    List<LocalityResolver.Match> m = resolver.scan("flat in andheri");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactlyInAnyOrder(id("Andheri East"), id("Andheri West"));
    assertThat(m.get(0).canonicalName()).isEqualTo("Andheri East / Andheri West");
  }

  @Test
  void twoWordAlias_disambiguates() {
    List<LocalityResolver.Match> m = resolver.scan("andheri west please");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("Andheri West"));
  }

  @Test
  void wordBoundaries_mansionIsNotSion() {
    List<LocalityResolver.Match> m = resolver.scan("a mansion in sion");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("Sion"));
    assertThat(m.get(0).tokenStart()).isEqualTo(3);
  }

  @Test
  void parelAndLowerParel_areDistinctMatches() {
    List<LocalityResolver.Match> m = resolver.scan("lower parel or parel");

    assertThat(m).extracting(LocalityResolver.Match::canonicalName).containsExactly("Lower Parel", "Parel");
  }

  @Test
  void fuzzyLayer_correctsAnInsertionTypo_atLowerConfidence() {
    List<LocalityResolver.Match> m = resolver.scan("near powaii");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("Powai"));
    assertThat(m.get(0).confidence()).isBetween(LocalityResolver.FUZZY_THRESHOLD, 0.99);
  }

  @Test
  void fuzzyLayer_ignoresShortTokensAndCommonWords() {
    assertThat(resolver.scan("pow wow")).isEmpty();
    assertThat(resolver.scan("best in the world")).isEmpty();
  }

  @Test
  void resolve_exactAliasWithoutSubstringGuessing() {
    assertThat(resolver.resolve("Bandra Kurla").map(LocalityResolver.Match::localityIds)).hasValue(List.of(id("BKC")));
    assertThat(resolver.resolve("bandra").map(LocalityResolver.Match::localityIds)).hasValue(List.of(id("Bandra")));
    assertThat(resolver.resolve("Goregaon").map(LocalityResolver.Match::confidence)).hasValue(LocalityResolver.EXACT);
  }

  @Test
  void resolve_fuzzyAndUnknown() {
    Optional<LocalityResolver.Match> fuzzy = resolver.resolve("powaii");
    assertThat(fuzzy).isPresent();
    assertThat(fuzzy.get().localityIds()).containsExactly(id("Powai"));
    assertThat(fuzzy.get().confidence()).isLessThan(LocalityResolver.CONFIDENT);

    assertThat(resolver.resolve("Atlantis")).isEmpty();
    assertThat(resolver.resolve("  ")).isEmpty();
    assertThat(resolver.resolve(null)).isEmpty();
  }

  @Test
  void nameOf_andVocabulary() {
    assertThat(resolver.nameOf(id("Powai"))).isEqualTo("Powai");
    assertThat(resolver.nameOf(UUID.randomUUID())).isEqualTo("Mumbai");
    assertThat(resolver.vocabulary())
        .contains("Powai (hiranandani)", "Kurla", "BKC (bandra kurla complex, bandra kurla)");
  }
}
```

Create `backend/src/test/java/com/flatmaite/search/LocationMentionsTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LocationMentionsTest {

  private LocalityResolver resolver;

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(List.of(locality("Andheri East", "andheri"), locality("BKC"), locality("Powai"), locality("Malad")));
    resolver = new LocalityResolver(repo);
    resolver.load();
  }

  private LocationMentions mentions(String text) {
    return LocationMentions.from(Tokens.of(text), resolver.scan(text));
  }

  @Test
  void plainMention_isHome() {
    LocationMentions m = mentions("private room in andheri");

    assertThat(m.home()).extracting(LocalityResolver.Match::canonicalName).containsExactly("Andheri East");
    assertThat(m.exclude()).isEmpty();
    assertThat(m.commute()).isEmpty();
  }

  @Test
  void liveHereWorkThere_splitsHomeAndCommute() {
    LocationMentions m = mentions("room in andheri, i work at bkc");

    assertThat(m.home()).extracting(LocalityResolver.Match::canonicalName).containsExactly("Andheri East");
    assertThat(m.commute()).map(LocalityResolver.Match::canonicalName).hasValue("BKC");
  }

  @Test
  void commuteCues() {
    assertThat(mentions("near bkc").commute()).isPresent();
    assertThat(mentions("close to bkc").commute()).isPresent();
    assertThat(mentions("office in bkc").commute()).isPresent();
    assertThat(mentions("within 20 min of bkc").commute()).isPresent();
    assertThat(mentions("flat in bkc").commute()).isEmpty();
  }

  @Test
  void negationCues_exclude() {
    assertThat(mentions("anywhere but andheri").exclude()).hasSize(1);
    assertThat(mentions("not in andheri").exclude()).hasSize(1);
    assertThat(mentions("except andheri").exclude()).hasSize(1);
    assertThat(mentions("avoid andheri").exclude()).hasSize(1);
    assertThat(mentions("other than andheri").exclude()).hasSize(1);
    assertThat(mentions("andheri nahi").exclude()).hasSize(1);
    assertThat(mentions("anywhere but andheri").home()).isEmpty();
  }

  @Test
  void aNegatedWordFurtherBack_doesNotExcludeThePlace() {
    // "no" is three tokens before "andheri" and applies to smokers, not the locality
    LocationMentions m = mentions("no smokers in andheri");

    assertThat(m.home()).hasSize(1);
    assertThat(m.exclude()).isEmpty();
  }

  @Test
  void onlyTheFirstCommuteCuedMention_isTheAnchor() {
    LocationMentions m = mentions("near bkc or near powai, room in malad");

    assertThat(m.commute()).map(LocalityResolver.Match::canonicalName).hasValue("BKC");
    assertThat(m.home()).extracting(LocalityResolver.Match::canonicalName).containsExactly("Powai", "Malad");
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='TrigramsTest,LocalityResolverTest,LocationMentionsTest' 2>&1 | tail -20`
Expected: `COMPILATION ERROR` (`Trigrams`, `LocationMentions`, `Match` not found).

- [ ] **Step 3: Implement `Trigrams`**

Create `backend/src/main/java/com/flatmaite/search/Trigrams.java`:

```java
package com.flatmaite.search;

import java.util.HashSet;
import java.util.Set;

/** pg_trgm-style similarity, in memory: Jaccard over trigrams of the string padded "  s ". */
final class Trigrams {

  private Trigrams() {}

  static double similarity(String a, String b) {
    Set<String> ta = trigrams(a);
    Set<String> tb = trigrams(b);
    if (ta.isEmpty() || tb.isEmpty()) {
      return 0.0;
    }
    int shared = 0;
    for (String t : ta) {
      if (tb.contains(t)) {
        shared++;
      }
    }
    int union = ta.size() + tb.size() - shared;
    return union == 0 ? 0.0 : (double) shared / union;
  }

  static Set<String> trigrams(String s) {
    Set<String> out = new HashSet<>();
    if (s == null || s.isEmpty()) {
      return out;
    }
    String padded = "  " + s + " ";
    for (int i = 0; i + 3 <= padded.length(); i++) {
      out.add(padded.substring(i, i + 3));
    }
    return out;
  }
}
```

- [ ] **Step 4: Rewrite `LocalityResolver`**

Overwrite `backend/src/main/java/com/flatmaite/search/LocalityResolver.java`:

```java
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
```

- [ ] **Step 5: Implement `LocationMentions`**

Create `backend/src/main/java/com/flatmaite/search/LocationMentions.java`:

```java
package com.flatmaite.search;

import com.flatmaite.search.LocalityResolver.Match;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the words around each locality match to decide its role: a place the user wants to live
 * in, a place to avoid, or a place to commute to. Only the first commute-cued mention becomes the
 * anchor; later ones fall back to home.
 */
public record LocationMentions(List<Match> home, List<Match> exclude, Optional<Match> commute) {

  static final Set<String> EXCLUDE_CUES = Set.of("not", "no", "except", "excluding", "avoid", "nahi");
  static final Set<String> EXCLUDE_AFTER = Set.of("nahi", "mat");
  static final Set<String> PREPOSITIONS = Set.of("in", "at", "to", "near", "around", "from", "of");
  static final Set<String> COMMUTE_CUES =
      Set.of("near", "nearby", "close", "around", "next", "within", "work", "working", "office", "commute", "commuting");
  private static final int COMMUTE_WINDOW = 4;

  public static LocationMentions from(List<Tokens.Token> tokens, List<Match> matches) {
    List<Match> home = new ArrayList<>();
    List<Match> exclude = new ArrayList<>();
    Match commute = null;
    for (Match m : matches) {
      if (isExcluded(tokens, m)) {
        exclude.add(m);
      } else if (commute == null && isCommute(tokens, m)) {
        commute = m;
      } else {
        home.add(m);
      }
    }
    return new LocationMentions(home, exclude, Optional.ofNullable(commute));
  }

  private static boolean isExcluded(List<Tokens.Token> tokens, Match m) {
    int i = m.tokenStart();
    String before1 = text(tokens, i - 1);
    String before2 = text(tokens, i - 2);
    String after = text(tokens, m.tokenEnd());
    if (EXCLUDE_AFTER.contains(after)) {
      return true;
    }
    if (EXCLUDE_CUES.contains(before1)) {
      return true;
    }
    if (PREPOSITIONS.contains(before1) && EXCLUDE_CUES.contains(before2)) {
      return true; // "not in andheri"
    }
    String pair = before2 + " " + before1;
    return pair.equals("other than") || pair.equals("anywhere but") || pair.equals("apart from");
  }

  private static boolean isCommute(List<Tokens.Token> tokens, Match m) {
    for (int k = 1; k <= COMMUTE_WINDOW; k++) {
      if (COMMUTE_CUES.contains(text(tokens, m.tokenStart() - k))) {
        return true;
      }
    }
    return false;
  }

  private static String text(List<Tokens.Token> tokens, int index) {
    return index < 0 || index >= tokens.size() ? "" : tokens.get(index).text();
  }
}
```

- [ ] **Step 6: Keep the module compiling — adapt the four `resolve` call sites minimally**

`resolve` now returns `Optional<Match>` instead of `UUID`. Tasks 3–7 rewrite these sites; for now make each compile with the first id:

`backend/src/main/java/com/flatmaite/search/KeywordIntentParser.java` lines 105–106 — replace

```java
    for (UUID id : localityResolver.scan(q)) {
      locations.add(new LocationRef(localityResolver.nameOf(id), id));
    }
```
with
```java
    for (LocalityResolver.Match m : localityResolver.scan(q)) {
      for (UUID id : m.localityIds()) {
        locations.add(new LocationRef(localityResolver.nameOf(id), id));
      }
    }
```

`backend/src/main/java/com/flatmaite/search/SearchPipeline.java` line 141 — replace `localityResolver.resolve(ref.name())` with `firstId(localityResolver.resolve(ref.name()))`; line 149 — replace `localityResolver.resolve(commute.place())` with `firstId(localityResolver.resolve(commute.place()))`; and add to the class:

```java
  private static UUID firstId(java.util.Optional<LocalityResolver.Match> match) {
    return match.map(m -> m.localityIds().get(0)).orElse(null);
  }
```

`backend/src/main/java/com/flatmaite/search/HybridRetriever.java` lines 305 and 315 — the same two substitutions (`firstId(localityResolver.resolve(...))`) and the same private static `firstId` helper added to `HybridRetriever`.

`NewQueryDetector.java` line 39 already compiles (`scan(q).isEmpty()`).

- [ ] **Step 7: Widen the seed**

In `backend/src/main/java/com/flatmaite/seed/SeedRunner.java`:

(a) `private static final int LISTING_COUNT = 50;` → `private static final int LISTING_COUNT = 80;`

(b) Replace the whole `LOCALITIES` array with:

```java
  private static final LocalitySeed[] LOCALITIES = {
    new LocalitySeed("Andheri East", 19.1136, 72.8697, new String[] {"andheri east", "andheri"}, 22000),
    new LocalitySeed("Andheri West", 19.1364, 72.8296, new String[] {"andheri west", "andheri"}, 25000),
    new LocalitySeed("Bandra", 19.0596, 72.8295, new String[] {"bandra west", "bandra east"}, 32000),
    new LocalitySeed("Powai", 19.1176, 72.9060, new String[] {"hiranandani", "hiranandani gardens", "iit bombay"}, 24000),
    new LocalitySeed("Lower Parel", 18.9962, 72.8330, new String[] {"lower parel", "lp"}, 33000),
    new LocalitySeed("Parel", 19.0090, 72.8400, new String[] {"parel"}, 30000),
    new LocalitySeed("Worli", 19.0176, 72.8172, new String[] {}, 38000),
    new LocalitySeed("Goregaon", 19.1663, 72.8526, new String[] {"goregaon east", "goregaon west", "film city"}, 16000),
    new LocalitySeed("Malad", 19.1874, 72.8484, new String[] {"malad west", "malad east", "mindspace"}, 14000),
    new LocalitySeed("BKC", 19.0653, 72.8693, new String[] {"bandra kurla complex", "bandra-kurla", "bandra kurla", "bkc road"}, 36000),
    new LocalitySeed("Kurla", 19.0726, 72.8845, new String[] {"kurla west", "kurla east"}, 13000),
    new LocalitySeed("Ghatkopar", 19.0790, 72.9080, new String[] {"ghatkopar east", "ghatkopar west"}, 15000),
    new LocalitySeed("Marol", 19.1197, 72.8823, new String[] {"marol naka", "mahakali"}, 20000),
    new LocalitySeed("Chakala", 19.1100, 72.8630, new String[] {"jb nagar", "j b nagar"}, 21000),
    new LocalitySeed("Sakinaka", 19.1050, 72.8880, new String[] {"saki naka"}, 16000),
    new LocalitySeed("Jogeshwari", 19.1360, 72.8490, new String[] {"jogeshwari east", "jogeshwari west"}, 17000),
    new LocalitySeed("Ram Mandir", 19.1480, 72.8450, new String[] {"ram mandir road"}, 16000),
    new LocalitySeed("Vile Parle", 19.0996, 72.8440, new String[] {"vile parle east", "vile parle west", "parle"}, 26000),
    new LocalitySeed("Santacruz", 19.0817, 72.8414, new String[] {"santa cruz", "santacruz east", "santacruz west"}, 28000),
    new LocalitySeed("Khar", 19.0700, 72.8340, new String[] {"khar west", "khar east"}, 32000),
    new LocalitySeed("Juhu", 19.1075, 72.8263, new String[] {"juhu beach"}, 34000),
    new LocalitySeed("Mahim", 19.0410, 72.8408, new String[] {}, 26000),
    new LocalitySeed("Dadar", 19.0178, 72.8478, new String[] {"dadar east", "dadar west", "shivaji park"}, 27000),
    new LocalitySeed("Matunga", 19.0270, 72.8553, new String[] {"matunga east", "matunga west"}, 26000),
    new LocalitySeed("Sion", 19.0390, 72.8619, new String[] {"sion east"}, 20000),
    new LocalitySeed("Wadala", 19.0176, 72.8562, new String[] {"wadala east"}, 22000),
    new LocalitySeed("Chembur", 19.0522, 72.9005, new String[] {"chembur east"}, 19000),
    new LocalitySeed("Vikhroli", 19.1080, 72.9280, new String[] {"vikhroli east", "vikhroli west"}, 18000),
    new LocalitySeed("Kanjurmarg", 19.1283, 72.9350, new String[] {"kanjur marg"}, 17000),
    new LocalitySeed("Bhandup", 19.1440, 72.9370, new String[] {}, 15000),
    new LocalitySeed("Mulund", 19.1726, 72.9564, new String[] {"mulund west"}, 17000),
    new LocalitySeed("Thane", 19.2183, 72.9781, new String[] {"thane west", "ghodbunder"}, 15000),
    new LocalitySeed("Kandivali", 19.2045, 72.8519, new String[] {"kandivali east", "kandivali west"}, 15000),
    new LocalitySeed("Borivali", 19.2307, 72.8567, new String[] {"borivali west", "borivali east"}, 16000),
    new LocalitySeed("Vashi", 19.0771, 72.9987, new String[] {"navi mumbai"}, 17000),
    new LocalitySeed("Airoli", 19.1590, 72.9986, new String[] {}, 15000),
    new LocalitySeed("Kharghar", 19.0330, 73.0650, new String[] {}, 13000),
    new LocalitySeed("Colaba", 18.9067, 72.8147, new String[] {"cuffe parade"}, 40000),
  };
```

(c) In `seedListings`, replace the five distribution lines

```java
    for (int i = 0; i < 18; i++) types[idx++] = ListingType.PRIVATE_ROOM;
    for (int i = 0; i < 10; i++) types[idx++] = ListingType.SHARED_ROOM;
    for (int i = 0; i < 10; i++) types[idx++] = ListingType.ENTIRE_APARTMENT;
    for (int i = 0; i < 8; i++) types[idx++] = ListingType.LOOKING_FOR_FLATMATE;
    for (int i = 0; i < 4; i++) types[idx++] = ListingType.REPLACEMENT;
```
with
```java
    for (int i = 0; i < 29; i++) types[idx++] = ListingType.PRIVATE_ROOM;
    for (int i = 0; i < 16; i++) types[idx++] = ListingType.SHARED_ROOM;
    for (int i = 0; i < 16; i++) types[idx++] = ListingType.ENTIRE_APARTMENT;
    for (int i = 0; i < 13; i++) types[idx++] = ListingType.LOOKING_FOR_FLATMATE;
    for (int i = 0; i < 6; i++) types[idx++] = ListingType.REPLACEMENT;
```

Check that nothing else in `SeedRunner` hard-codes 50 or indexes `LOCALITIES` by a fixed position: run `grep -n "50\b\|LOCALITIES\[" src/main/java/com/flatmaite/seed/SeedRunner.java` — the only `LOCALITIES[` uses are `LOCALITIES[i % LOCALITIES.length]` and `LOCALITIES[(i + 2) % LOCALITIES.length]`, which need no change.

- [ ] **Step 8: Run the new tests and the whole pure suite**

Run: `./mvnw test -Dtest='TrigramsTest,LocalityResolverTest,LocationMentionsTest,NewQueryDetectorTest,MatchScorerTest,RankFusionTest,LexicalQueryTest,RefinementHeuristicsTest,SemanticTextTest,SearchIntentFreeTextTest,MockIntentLlmTest,OpenAiIntentLlmFinishTest,TokensTest,NumberWordsTest,RentalVocabularyTest,LifestyleCompatibilityTest,CommuteEstimatorNearbyTest' 2>&1 | tail -30`
Expected: every class `Failures: 0` (`TrigramsTest` 4, `LocalityResolverTest` 10, `LocationMentionsTest` 6); `BUILD SUCCESS`. `NewQueryDetectorTest`'s 13 existing cases still pass with the new resolver.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/flatmaite/search/Trigrams.java src/main/java/com/flatmaite/search/LocalityResolver.java src/main/java/com/flatmaite/search/LocationMentions.java src/main/java/com/flatmaite/search/KeywordIntentParser.java src/main/java/com/flatmaite/search/SearchPipeline.java src/main/java/com/flatmaite/search/HybridRetriever.java src/main/java/com/flatmaite/seed/SeedRunner.java src/test/java/com/flatmaite/search/TrigramsTest.java src/test/java/com/flatmaite/search/LocalityResolverTest.java src/test/java/com/flatmaite/search/LocationMentionsTest.java
git commit -m "$(cat <<'EOF'
Resolve localities by word-boundary longest match with a fuzzy fallback; widen the seed

"bandra kurla complex" is BKC alone, "andheri" names both halves, and every
match carries its position so the words around it can be read. 39 seeded
localities, 80 listings.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `SearchIntent` additions, exclusion filters, one commute default, frontend mirror

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/SearchIntent.java` — two record components + `DEFAULT_COMMUTE_MINUTES`
- Modify: `backend/src/main/java/com/flatmaite/ai/MockLlms.java` — merge the two new fields on refinement
- Modify: `backend/src/main/java/com/flatmaite/listing/ListingFilters.java` — `excludeLocalityIds`
- Modify: `backend/src/main/java/com/flatmaite/listing/ListingQueryService.java` — `buildWhere` exclusion clause
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` — `requestedLocalityIds`, `excludedLocalityIds`, exclusion in `toFilters`/`admittedLocalityIds`/flatmate `where`; remove the temporary `firstId`
- Modify: `backend/src/main/java/com/flatmaite/search/MatchScorer.java:92`, `backend/src/main/java/com/flatmaite/search/RefinementHeuristics.java:31`, `backend/src/main/java/com/flatmaite/search/KeywordIntentParser.java:118` — `45` → `SearchIntent.DEFAULT_COMMUTE_MINUTES`
- Modify: `frontend/src/lib/ai-client.ts` — type mirror + two chips
- Test: `backend/src/test/java/com/flatmaite/listing/ListingQueryServiceWhereTest.java` (new); `backend/src/test/java/com/flatmaite/ai/MockIntentLlmTest.java` (one test added)

**Interfaces:**
- Consumes: `LocalityResolver.resolve → Optional<Match>` (Task 2).
- Produces: `SearchIntent.excludeLocations : List<LocationRef>`, `SearchIntent.unresolvedLocations : List<String>`, `SearchIntent.DEFAULT_COMMUTE_MINUTES = 30`; `ListingFilters.excludeLocalityIds : List<UUID>`; `HybridRetriever.requestedLocalityIds(SearchIntent) : List<UUID>` (home ids, every id of an ambiguous alias, minus exclusions), `HybridRetriever.excludedLocalityIds(SearchIntent) : List<UUID>`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/flatmaite/listing/ListingQueryServiceWhereTest.java`:

```java
package com.flatmaite.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingQueryServiceWhereTest {

  @Test
  void excludeLocalities_addsANullSafeNotInClause() {
    UUID malad = UUID.randomUUID();
    Map<String, Object> params = new LinkedHashMap<>();

    String where =
        ListingQueryService.buildWhere(
            ListingFilters.builder().excludeLocalityIds(List.of(malad)).build(), params);

    // LEFT JOIN properties: a listing without a property must survive the exclusion
    assertThat(where).contains("(p.locality_id IS NULL OR p.locality_id NOT IN (:excludeLocalityIds))");
    assertThat(params).containsEntry("excludeLocalityIds", List.of(malad));
  }

  @Test
  void noExclusions_noClause() {
    Map<String, Object> params = new LinkedHashMap<>();

    String where = ListingQueryService.buildWhere(ListingFilters.empty(), params);

    assertThat(where).doesNotContain("NOT IN");
    assertThat(params).doesNotContainKey("excludeLocalityIds");
  }
}
```

Add to `backend/src/test/java/com/flatmaite/ai/MockIntentLlmTest.java`:

```java
  @Test
  void refinement_carriesExclusionsAndUnresolvedNamesForward() {
    SearchIntent prior =
        SearchIntent.builder()
            .budgetMax(20000)
            .excludeLocations(List.of(new SearchIntent.LocationRef("Andheri East", null)))
            .unresolvedLocations(List.of("Hiranandani Gardens"))
            .originalQuery("room not in andheri near hiranandani gardens")
            .build();

    SearchIntent refined = llm().extract("with a balcony", prior);

    assertThat(refined.excludeLocations()).extracting(SearchIntent.LocationRef::name).containsExactly("Andheri East");
    assertThat(refined.unresolvedLocations()).containsExactly("Hiranandani Gardens");
    assertThat(SearchIntent.DEFAULT_COMMUTE_MINUTES).isEqualTo(30);
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='ListingQueryServiceWhereTest,MockIntentLlmTest' 2>&1 | tail -20`
Expected: `COMPILATION ERROR` — `cannot find symbol: method excludeLocalityIds` / `excludeLocations` / `DEFAULT_COMMUTE_MINUTES`.

- [ ] **Step 3: Extend `SearchIntent`**

In `backend/src/main/java/com/flatmaite/search/SearchIntent.java`, change the record header so the two new components follow `commuteTo`:

```java
    CommuteTo commuteTo,
    List<LocationRef> excludeLocations,
    List<String> unresolvedLocations,
    Boolean verifiedOnly,
```

(the components before `commuteTo` and after `verifiedOnly` are unchanged). Update the class javadoc's last sentence to: `The original natural-language query is always preserved; excludeLocations are places to avoid and unresolvedLocations are names no resolver layer could place.`

Add, next to `MAX_FREE_TEXT_CHARS`:

```java
  /** Commute radius when the user names a workplace without a time — replaces four scattered 45s. */
  public static final int DEFAULT_COMMUTE_MINUTES = 30;
```

- [ ] **Step 4: Replace the three remaining `45` commute defaults**

- `backend/src/main/java/com/flatmaite/search/MatchScorer.java` line 92: `: 45;` → `: SearchIntent.DEFAULT_COMMUTE_MINUTES;`
- `backend/src/main/java/com/flatmaite/search/RefinementHeuristics.java` line 31: `prior.commuteTo().maxMinutes() == null ? 45 : prior.commuteTo().maxMinutes();` → `prior.commuteTo().maxMinutes() == null ? SearchIntent.DEFAULT_COMMUTE_MINUTES : prior.commuteTo().maxMinutes();`
- `backend/src/main/java/com/flatmaite/search/KeywordIntentParser.java` line 118: `maxMinutes == null ? 45 : maxMinutes` → `maxMinutes == null ? SearchIntent.DEFAULT_COMMUTE_MINUTES : maxMinutes`

(`MatchScorer.java:160` has `45.0` for the availability gap — unrelated, leave it. `HybridRetriever`'s `45` goes in Step 6.)

- [ ] **Step 5: Merge the new fields in `MockIntentLlm`**

In `backend/src/main/java/com/flatmaite/ai/MockLlms.java`, directly after the line `.locations(firstNonNull(parsed.locations(), prior.locations()))` add:

```java
          .excludeLocations(firstNonNull(parsed.excludeLocations(), prior.excludeLocations()))
          .unresolvedLocations(firstNonNull(parsed.unresolvedLocations(), prior.unresolvedLocations()))
```

- [ ] **Step 6: Filters and admission**

`backend/src/main/java/com/flatmaite/listing/ListingFilters.java` — add a component after `localityIds`:

```java
    List<UUID> excludeLocalityIds,
```

`backend/src/main/java/com/flatmaite/listing/ListingQueryService.java` — in `buildWhere`, directly after the `localityIds` block, add:

```java
    if (f.excludeLocalityIds() != null && !f.excludeLocalityIds().isEmpty()) {
      // LEFT JOIN: a listing without a property has a NULL locality and must survive the exclusion
      where.append(" AND (p.locality_id IS NULL OR p.locality_id NOT IN (:excludeLocalityIds))");
      params.put("excludeLocalityIds", f.excludeLocalityIds());
    }
```

`backend/src/main/java/com/flatmaite/search/HybridRetriever.java`:

(a) In `toFilters`, after `.localityIds(admittedLocalityIds(intent))` add `.excludeLocalityIds(excludedLocalityIds(intent))`.

(b) In `retrieveFlatmates`, directly after the `localityIds` block (`if (!localityIds.isEmpty()) { ... }`) add:

```java
    List<UUID> excludedIds = excludedLocalityIds(intent);
    if (!excludedIds.isEmpty()) {
      where.append(" AND NOT (fp.locality_ids && CAST(:exclIds AS uuid[]))");
      params.put("exclIds", excludedIds.toArray(UUID[]::new));
    }
```

(c) Replace `admittedLocalityIds` (and delete the temporary `firstId` helper from Task 2) with:

```java
  /** Home localities the user named — every id of an ambiguous alias — minus exclusions. */
  public List<UUID> requestedLocalityIds(SearchIntent intent) {
    List<UUID> ids = new ArrayList<>();
    if (intent.locations() != null) {
      for (SearchIntent.LocationRef ref : intent.locations()) {
        for (UUID id : idsOf(ref)) {
          if (!ids.contains(id)) {
            ids.add(id);
          }
        }
      }
    }
    ids.removeAll(excludedLocalityIds(intent));
    return ids;
  }

  public List<UUID> excludedLocalityIds(SearchIntent intent) {
    List<UUID> ids = new ArrayList<>();
    if (intent.excludeLocations() != null) {
      for (SearchIntent.LocationRef ref : intent.excludeLocations()) {
        for (UUID id : idsOf(ref)) {
          if (!ids.contains(id)) {
            ids.add(id);
          }
        }
      }
    }
    return ids;
  }

  private List<UUID> idsOf(SearchIntent.LocationRef ref) {
    if (ref.localityId() != null) {
      return List.of(ref.localityId());
    }
    return localityResolver.resolve(ref.name()).map(LocalityResolver.Match::localityIds).orElse(List.of());
  }

  private UUID commuteAnchor(SearchIntent intent) {
    if (intent.commuteTo() == null) {
      return null;
    }
    if (intent.commuteTo().localityId() != null) {
      return intent.commuteTo().localityId();
    }
    return localityResolver.resolve(intent.commuteTo().place()).map(m -> m.localityIds().get(0)).orElse(null);
  }

  /**
   * Requested localities ∪ commute-radius expansion, minus exclusions. Empty list = no locality
   * hard filter (the location signal then only affects scoring).
   */
  public List<UUID> admittedLocalityIds(SearchIntent intent) {
    List<UUID> ids = new ArrayList<>(requestedLocalityIds(intent));
    UUID anchor = commuteAnchor(intent);
    if (anchor != null) {
      int maxMinutes =
          intent.commuteTo().maxMinutes() == null
              ? SearchIntent.DEFAULT_COMMUTE_MINUTES
              : intent.commuteTo().maxMinutes();
      for (UUID locality : allLocalityIds()) {
        Integer minutes = commuteEstimator.minutesBetween(locality, anchor);
        if (minutes != null && minutes <= maxMinutes && !ids.contains(locality)) {
          ids.add(locality);
        }
      }
    }
    ids.removeAll(excludedLocalityIds(intent));
    return ids;
  }
```

- [ ] **Step 7: Frontend mirror and chips**

In `frontend/src/lib/ai-client.ts`, inside `export interface SearchIntent`, after the `commuteTo` line add:

```ts
  excludeLocations?: { name: string; localityId: string | null }[] | null;
  unresolvedLocations?: string[] | null;
```

In `chipsFromIntent`, directly after the `for (const loc of intent.locations ?? []) { ... }` loop add:

```ts
  for (const loc of intent.excludeLocations ?? []) {
    chips.push({
      key: `excl:${loc.name}`,
      icon: "🚫",
      label: "Not in",
      value: loc.name,
      remove: (i) => ({
        ...i,
        excludeLocations: (i.excludeLocations ?? []).filter((l) => l.name !== loc.name),
      }),
    });
  }
  for (const name of intent.unresolvedLocations ?? []) {
    chips.push({
      key: `unres:${name}`,
      icon: "📍?",
      label: "Couldn't place",
      value: name,
      remove: (i) => ({
        ...i,
        unresolvedLocations: (i.unresolvedLocations ?? []).filter((n) => n !== name),
      }),
    });
  }
```

- [ ] **Step 8: Verify backend and frontend compile and the tests pass**

Run: `./mvnw test -Dtest='ListingQueryServiceWhereTest,MockIntentLlmTest,MatchScorerTest,RefinementHeuristicsTest,NewQueryDetectorTest,LocalityResolverTest' 2>&1 | tail -20`
Expected: `ListingQueryServiceWhereTest` 2, `MockIntentLlmTest` 5, others unchanged, `Failures: 0`, `BUILD SUCCESS`.

Run (from `frontend/`): `npx tsc --noEmit 2>&1 | tail -5` — expected no output (clean). If `node_modules` is missing, run `npm install` first.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/flatmaite/search/SearchIntent.java src/main/java/com/flatmaite/ai/MockLlms.java src/main/java/com/flatmaite/listing/ListingFilters.java src/main/java/com/flatmaite/listing/ListingQueryService.java src/main/java/com/flatmaite/search/HybridRetriever.java src/main/java/com/flatmaite/search/MatchScorer.java src/main/java/com/flatmaite/search/RefinementHeuristics.java src/main/java/com/flatmaite/search/KeywordIntentParser.java src/test/java/com/flatmaite/listing/ListingQueryServiceWhereTest.java src/test/java/com/flatmaite/ai/MockIntentLlmTest.java ../frontend/src/lib/ai-client.ts
git commit -m "$(cat <<'EOF'
Let an intent exclude localities and carry unplaced names; one commute default

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Distance-aware admission and scoring; nearby note; retire the nearby relaxers

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java` — `Search` group
- Modify: `backend/src/main/resources/application.yml` — `flatmaite.search.nearby-radius-minutes`
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` — `admittedLocalityIds` via `nearestLocalities`; inject `FlatmaiteProperties`; delete `allLocalityIds`
- Modify: `backend/src/main/java/com/flatmaite/search/MatchScorer.java` — `ListingCandidate` fields, location component
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` — anchor = nearest requested locality, labels, note, flatmate overlap on requested ids, remove `nearbyAreaRelaxers`
- Test: `backend/src/test/java/com/flatmaite/search/MatchScorerTest.java` (helper + one test), `backend/src/test/java/com/flatmaite/search/LocationWideningIntegrationTest.java` (new, Testcontainers)

**Interfaces:**
- Consumes: `requestedLocalityIds`, `excludedLocalityIds`, `commuteAnchor` (Task 3); `CommuteEstimator.nearestLocalities(UUID anchor, int maxMinutes, int limit) : List<Nearby(localityId, minutes)>` (existing).
- Produces: `FlatmaiteProperties.getSearch().getNearbyRadiusMinutes()`; `MatchScorer.ListingCandidate(Listing, UUID localityId, String localityName, boolean emailVerified, boolean phoneVerified, boolean idOrPropertyVerified, Retrieval retrieval, Integer commuteMinutes, String anchorName, boolean anchorIsCommute, int radiusMinutes, boolean inPreferredLocality)`; `MatchScorer.MIN_LOCATION_SCORE = 0.3`; `AiSearchResponse.note` carries `"Also showing nearby areas within ~%d min."` when a home outside the requested localities is returned.

- [ ] **Step 1: Update `MatchScorerTest` for the new candidate shape and add the decay test**

In `backend/src/test/java/com/flatmaite/search/MatchScorerTest.java`, replace the `candidate(...)` helper with:

```java
  private ListingCandidate candidate(Listing l, boolean preferred, Integer commute, Retrieval retrieval) {
    return new ListingCandidate(
        l, UUID.randomUUID(), "BKC", true, true, true, retrieval, commute, "BKC", true, 30, preferred);
  }
```

and add this test after `commuteBeyondPreference_reducesLocationScore`:

```java
  @Test
  void nearbyLocality_decaysWithDistance_andFloorsAtMinimum() {
    SearchIntent intent =
        SearchIntent.builder()
            .searchTarget(SearchTarget.PROPERTIES)
            .locations(List.of(new LocationRef("Goregaon", UUID.randomUUID())))
            .build();
    Listing l = listing(20000, null, null);

    MatchScorer.Scored near =
        MatchScorer.scoreListing(intent, new ListingCandidate(l, UUID.randomUUID(), "Malad", true, true, true, SEMANTIC_TOP, 12, "Goregaon", false, 25, false));
    MatchScorer.Scored edge =
        MatchScorer.scoreListing(intent, new ListingCandidate(l, UUID.randomUUID(), "Kandivali", true, true, true, SEMANTIC_TOP, 25, "Goregaon", false, 25, false));
    MatchScorer.Scored far =
        MatchScorer.scoreListing(intent, new ListingCandidate(l, UUID.randomUUID(), "Colaba", true, true, true, SEMANTIC_TOP, 60, "Goregaon", false, 25, false));

    assertThat(component(near, "location").score()).isCloseTo(0.76, within(0.001));
    assertThat(component(edge, "location").score()).isCloseTo(0.5, within(0.001));
    assertThat(component(far, "location").score()).isEqualTo(MatchScorer.MIN_LOCATION_SCORE);
    assertThat(component(near, "location").detail()).isEqualTo("~12 min from Goregaon (estimate)");
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=MatchScorerTest 2>&1 | tail -15`
Expected: `COMPILATION ERROR` — `ListingCandidate` constructor arity / `MIN_LOCATION_SCORE` not found.

- [ ] **Step 3: Configuration**

`backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java` — add a field `private Search search = new Search();` next to `storage`, and the class:

```java
  @Getter
  @Setter
  public static class Search {
    /** A named home locality also admits every locality within this many estimated minutes. */
    private int nearbyRadiusMinutes = 25;
  }
```

`backend/src/main/resources/application.yml` — under `flatmaite:` add (after the `storage:` block):

```yaml
  search:
    # a named home locality also admits every locality within this many estimated minutes
    nearby-radius-minutes: ${SEARCH_NEARBY_RADIUS_MINUTES:25}
```

- [ ] **Step 4: `MatchScorer`**

Replace the `ListingCandidate` record with:

```java
  public record ListingCandidate(
      Listing listing,
      UUID localityId,
      String localityName,
      boolean emailVerified,
      boolean phoneVerified,
      boolean idOrPropertyVerified,
      HybridRetriever.Retrieval retrieval,
      Integer commuteMinutes,
      String anchorName,
      boolean anchorIsCommute,
      int radiusMinutes,
      boolean inPreferredLocality) {}
```

Add near the top of the class (after `private MatchScorer() {}`):

```java
  /** Location score for anything admitted from the surrounding radius never drops below this. */
  public static final double MIN_LOCATION_SCORE = 0.3;
```

Replace the location block (from `// location / commute (.20)` through `parts.add(new Component("location", 0.20, score, detail));` and its closing `}`) with:

```java
    // location (.20) — applies when the intent has any location signal. In a requested locality
    // → 1.0; anything admitted from the surrounding radius decays with distance to the nearest
    // requested locality (or the commute anchor), floored so nearby never reads as "wrong".
    boolean hasLocationSignal =
        (intent.locations() != null && !intent.locations().isEmpty()) || intent.commuteTo() != null;
    if (hasLocationSignal) {
      double score;
      String detail;
      if (c.inPreferredLocality()) {
        score = 1.0;
        detail = "In %s — one of your preferred areas".formatted(c.localityName());
      } else if (c.commuteMinutes() != null) {
        int radius = Math.max(1, c.radiusMinutes());
        score = Math.max(MIN_LOCATION_SCORE, 1.0 - c.commuteMinutes() / (2.0 * radius));
        detail =
            "~%d min %s %s (estimate)"
                .formatted(c.commuteMinutes(), c.anchorIsCommute() ? "to" : "from", c.anchorName());
      } else {
        score = MIN_LOCATION_SCORE;
        detail = "Outside your preferred areas";
      }
      parts.add(new Component("location", 0.20, score, detail));
    }
```

(The `SearchIntent.DEFAULT_COMMUTE_MINUTES` reference introduced in Task 3 at the old line 92 disappears with this block — that is expected.)

- [ ] **Step 5: `HybridRetriever` — widen by radius**

Add a constructor dependency `private final FlatmaiteProperties props;` (import `com.flatmaite.common.config.FlatmaiteProperties`) and `import java.util.HashMap;`. Replace `admittedLocalityIds` with:

```java
  /**
   * Requested localities plus everything within the nearby radius of each; plus the commute
   * radius when a workplace is named; minus exclusions. Requested ids come first, then by minutes.
   * Empty list = no locality hard filter (the location signal then only affects scoring).
   */
  public List<UUID> admittedLocalityIds(SearchIntent intent) {
    List<UUID> requested = requestedLocalityIds(intent);
    Set<UUID> excluded = new HashSet<>(excludedLocalityIds(intent));
    LinkedHashSet<UUID> admitted = new LinkedHashSet<>(requested);
    Map<UUID, Integer> nearbyMinutes = new HashMap<>();
    int radius = props.getSearch().getNearbyRadiusMinutes();
    for (UUID id : requested) {
      for (CommuteEstimator.Nearby n : commuteEstimator.nearestLocalities(id, radius, Integer.MAX_VALUE)) {
        nearbyMinutes.merge(n.localityId(), n.minutes(), Math::min);
      }
    }
    UUID anchor = commuteAnchor(intent);
    if (anchor != null) {
      int maxMinutes =
          intent.commuteTo().maxMinutes() == null
              ? SearchIntent.DEFAULT_COMMUTE_MINUTES
              : intent.commuteTo().maxMinutes();
      nearbyMinutes.merge(anchor, 0, Math::min);
      for (CommuteEstimator.Nearby n : commuteEstimator.nearestLocalities(anchor, maxMinutes, Integer.MAX_VALUE)) {
        nearbyMinutes.merge(n.localityId(), n.minutes(), Math::min);
      }
    }
    nearbyMinutes.entrySet().stream()
        .sorted(Map.Entry.comparingByValue())
        .forEach(e -> admitted.add(e.getKey()));
    admitted.removeAll(excluded);
    return new ArrayList<>(admitted);
  }
```

Delete the now-unused `allLocalityIds()` method.

- [ ] **Step 6: `SearchPipeline` — anchor, labels, note, relaxers**

Add a constructor dependency `private final FlatmaiteProperties props;` (import `com.flatmaite.common.config.FlatmaiteProperties`).

(a) Add a private record next to the other helpers at the top of the class body:

```java
  /** Ranked homes plus whether any came from outside the requested localities. */
  private record Homes(List<AiResult> results, boolean includesNearby, int radiusMinutes) {}
```

(b) Replace the body of the 5-argument `search(...)` method with:

```java
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
```

(c) Change `searchHomes`' return type to `Homes` and replace everything from the comment `// preferred localities (not the commute-expanded set)` through the method's final `return out;` with:

```java
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
    return best == null ? preferred.iterator().next() : best;
  }
```

(d) In `searchFlatmates`, change `Set<UUID> wantedLocalities = new HashSet<>(retriever.admittedLocalityIds(intent));` to `Set<UUID> wantedLocalities = new HashSet<>(retriever.requestedLocalityIds(intent));`.

(e) In `computeRelaxers`, delete the comment block beginning `// Nearby areas beat "everywhere":` and the line `out.addAll(nearbyAreaRelaxers(intent));`. Delete the constants `NEARBY_MAX_MINUTES`, `NEARBY_MAX_SUGGESTIONS`, the `nearbyAreaRelaxers` method and its javadoc. Remove imports that become unused (`java.util.Objects`, `java.util.stream.Collectors`) if the compiler reports them unused.

- [ ] **Step 7: Integration test**

Create `backend/src/test/java/com/flatmaite/search/LocationWideningIntegrationTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchDtos.AiResult;
import com.flatmaite.search.SearchDtos.AiSearchResponse;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A named home locality admits its ~25-minute neighbourhood, ranks exact matches first, labels the
 * rest with their distance, and says so in the note. Against the deterministic seed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("seed")
class LocationWideningIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired HybridRetriever retriever;
  @Autowired SearchPipeline pipeline;
  @Autowired LocalityResolver resolver;
  @Autowired LocalityRepository localities;
  @Autowired ListingQueryService listingQueryService;

  private UUID idOf(String name) {
    return resolver.resolve(name).orElseThrow().localityIds().get(0);
  }

  private SearchIntent homeIn(String name) {
    return SearchIntent.builder()
        .searchTarget(SearchTarget.PROPERTIES)
        .locations(List.of(new LocationRef(name, idOf(name))))
        .originalQuery("room in " + name)
        .freeText("room in " + name)
        .build();
  }

  /** The first western-suburb locality that actually has an active seed listing. */
  private String anchoredLocality() {
    for (String name : List.of("Goregaon", "Malad", "Ram Mandir", "Jogeshwari", "Andheri West", "Andheri East")) {
      long total =
          listingQueryService
              .findIds(ListingFilters.builder().localityIds(List.of(idOf(name))).build(), ListingQueryService.Sort.NEWEST, 0, 1)
              .total();
      if (total > 0) {
        return name;
      }
    }
    throw new AssertionError("no seeded active listing in the Goregaon cluster");
  }

  @Test
  void seedHasTheWiderLocalityTable() {
    assertThat(localities.count()).isGreaterThanOrEqualTo(35);
    assertThat(resolver.resolve("Ram Mandir")).isPresent();
    assertThat(resolver.resolve("Andheri").orElseThrow().localityIds()).hasSize(2);
    assertThat(resolver.resolve("parel").orElseThrow().canonicalName()).isEqualTo("Parel");
  }

  @Test
  void namedLocality_admitsItsNeighbourhood_requestedFirst() {
    List<UUID> admitted = retriever.admittedLocalityIds(homeIn("Goregaon"));

    assertThat(admitted.get(0)).isEqualTo(idOf("Goregaon"));
    assertThat(admitted).contains(idOf("Ram Mandir"), idOf("Malad"), idOf("Jogeshwari"));
    assertThat(admitted).doesNotContain(idOf("Colaba"), idOf("Thane"));
  }

  @Test
  void exclusion_removesFromTheAdmittedSet() {
    SearchIntent intent =
        homeIn("Goregaon").toBuilder()
            .excludeLocations(List.of(new LocationRef("Malad", idOf("Malad"))))
            .build();

    assertThat(retriever.admittedLocalityIds(intent)).contains(idOf("Ram Mandir")).doesNotContain(idOf("Malad"));
  }

  @Test
  void nearbyHomes_areLabelledAndNoted_exactOnesAreNot() {
    String name = anchoredLocality();

    AiSearchResponse r = pipeline.search(homeIn(name), null, null, UUID.randomUUID());

    List<AiResult> exact = r.homes().stream().filter(h -> h.commuteLabel() == null).toList();
    List<AiResult> nearby = r.homes().stream().filter(h -> h.commuteLabel() != null).toList();
    assertThat(exact).isNotEmpty();
    assertThat(exact)
        .allSatisfy(h -> assertThat(h.scoreBreakdown())
            .anySatisfy(cmp -> assertThat(cmp.detail()).contains("one of your preferred areas")));
    assertThat(nearby).isNotEmpty();
    assertThat(nearby).allSatisfy(h -> assertThat(h.commuteLabel()).contains("min from " + name));
    assertThat(r.note()).contains("nearby areas within ~25 min");
  }
}
```

- [ ] **Step 8: Run the unit tests, then the integration test (Docker)**

Run: `./mvnw test -Dtest='MatchScorerTest,CommuteEstimatorNearbyTest,ListingQueryServiceWhereTest' 2>&1 | tail -15`
Expected: `MatchScorerTest` 11 tests, `Failures: 0`.

Run: `./mvnw test -Dtest='LocationWideningIntegrationTest,SearchPipelineIntegrationTest,HybridRetrieverIntegrationTest' 2>&1 | tail -25`
Expected: `LocationWideningIntegrationTest` 4, `SearchPipelineIntegrationTest` 3, `HybridRetrieverIntegrationTest` 4, `Failures: 0`, `BUILD SUCCESS`. If `nearbyHomes_areLabelledAndNoted_exactOnesAreNot` fails on `nearby` being empty, do not loosen it — report BLOCKED with the locality counts printed by `SELECT l.name, count(*) FROM listings ls JOIN properties p ON p.id=ls.property_id JOIN localities l ON l.id=p.locality_id WHERE ls.status='ACTIVE' GROUP BY 1` (run via the seed container) so the controller can choose a different anchor cluster.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java src/main/resources/application.yml src/main/java/com/flatmaite/search/HybridRetriever.java src/main/java/com/flatmaite/search/MatchScorer.java src/main/java/com/flatmaite/search/SearchPipeline.java src/test/java/com/flatmaite/search/MatchScorerTest.java src/test/java/com/flatmaite/search/LocationWideningIntegrationTest.java
git commit -m "$(cat <<'EOF'
Admit a named locality's neighbourhood and rank it by distance

Three Goregaon matches no longer end the page: everything within ~25
estimated minutes is admitted, scored by distance to the nearest requested
locality, labelled, and announced in the note. The nearby relaxer buttons
are redundant and go.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `KeywordIntentParser` — floors and ceilings, spelled-out amounts, negation-aware verified, cue-based locations

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/KeywordIntentParser.java` (full rewrite)
- Test: `backend/src/test/java/com/flatmaite/search/KeywordIntentParserTest.java` (new)

**Interfaces:**
- Consumes: `Tokens`, `NumberWords.NUMBER_RUN`/`parse` (Task 1); `LocalityResolver.scan`, `LocationMentions.from` (Task 2); `SearchIntent.excludeLocations`, `DEFAULT_COMMUTE_MINUTES` (Task 3).
- Produces: unchanged public API `SearchIntent parse(String query)`; package-private `static final Set<String> FLOOR_CUES`, `CEILING_CUES` (for the eval report in WS3).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/flatmaite/search/KeywordIntentParserTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KeywordIntentParserTest {

  private KeywordIntentParser parser;

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", "hiranandani"),
                locality("Goregaon"),
                locality("Andheri East", "andheri east", "andheri"),
                locality("Andheri West", "andheri west", "andheri"),
                locality("BKC", "bandra kurla complex"),
                locality("Malad")));
    LocalityResolver resolver = new LocalityResolver(repo);
    resolver.load();
    parser = new KeywordIntentParser(resolver);
  }

  @Test
  void ceilingCues_setBudgetMax() {
    assertThat(parser.parse("private room in goregaon under 25000").budgetMax()).isEqualTo(25000);
    assertThat(parser.parse("room upto 30k").budgetMax()).isEqualTo(30000);
    assertThat(parser.parse("max 1 lakh flat").budgetMax()).isEqualTo(100000);
    assertThat(parser.parse("budget 22k").budgetMax()).isEqualTo(22000);
    assertThat(parser.parse("not more than 18k").budgetMax()).isEqualTo(18000);
    assertThat(parser.parse("private room in goregaon under 25000").budgetMin()).isNull();
  }

  @Test
  void floorCues_setBudgetMin_notMax() {
    SearchIntent i = parser.parse("flat in andheri more than 30000");

    assertThat(i.budgetMin()).isEqualTo(30000);
    assertThat(i.budgetMax()).isNull();
    assertThat(parser.parse("rooms above 20k please").budgetMin()).isEqualTo(20000);
    assertThat(parser.parse("at least 15k").budgetMin()).isEqualTo(15000);
  }

  @Test
  void ranges_setBoth() {
    SearchIntent between = parser.parse("room between 20k and 30k in powai");
    assertThat(between.budgetMin()).isEqualTo(20000);
    assertThat(between.budgetMax()).isEqualTo(30000);

    SearchIntent to = parser.parse("20000 to 25000 rent");
    assertThat(to.budgetMin()).isEqualTo(20000);
    assertThat(to.budgetMax()).isEqualTo(25000);

    SearchIntent dash = parser.parse("25k-35k 2bhk");
    assertThat(dash.budgetMin()).isEqualTo(25000);
    assertThat(dash.budgetMax()).isEqualTo(35000);
  }

  @Test
  void bareNumbers_areBudgetsOnlyWithMoneyContext() {
    assertThat(parser.parse("flat near pincode 400076").budgetMax()).isNull();
    assertThat(parser.parse("1200 sqft flat in powai").budgetMax()).isNull();
    assertThat(parser.parse("room for 25000").budgetMax()).isEqualTo(25000);
    assertThat(parser.parse("25000 rent in powai").budgetMax()).isEqualTo(25000);
    assertThat(parser.parse("rs 25000 in powai").budgetMax()).isEqualTo(25000);
  }

  @Test
  void spelledOutAmounts_matchTheGlossary() {
    assertThat(parser.parse("room for twenty five thousand in malad").budgetMax()).isEqualTo(25000);
    assertThat(parser.parse("flat around one and a half lakh").budgetMax()).isEqualTo(150000);
  }

  @Test
  void depositStaysSeparateFromRent() {
    SearchIntent i = parser.parse("2 lakh deposit, 30k rent");

    assertThat(i.maxDeposit()).isEqualTo(200000);
    assertThat(i.budgetMax()).isEqualTo(30000);
    assertThat(parser.parse("deposit under 50k").maxDeposit()).isEqualTo(50000);
  }

  @Test
  void verified_onlyWhenNotNegated() {
    assertThat(parser.parse("verified listings only").verifiedOnly()).isTrue();
    assertThat(parser.parse("not verified listings are fine").verifiedOnly()).isNull();
    assertThat(parser.parse("unverified is ok").verifiedOnly()).isNull();
    assertThat(parser.parse("non-verified also fine").verifiedOnly()).isNull();
  }

  @Test
  void liveHere_workThere() {
    SearchIntent i = parser.parse("room in andheri, i work at bkc");

    assertThat(i.locations()).extracting(SearchIntent.LocationRef::name)
        .containsExactlyInAnyOrder("Andheri East", "Andheri West");
    assertThat(i.commuteTo().place()).isEqualTo("BKC");
    assertThat(i.commuteTo().maxMinutes()).isEqualTo(SearchIntent.DEFAULT_COMMUTE_MINUTES);
  }

  @Test
  void commuteMinutes_whenStated() {
    SearchIntent i = parser.parse("near bkc within 20 mins under 25k");

    assertThat(i.commuteTo().maxMinutes()).isEqualTo(20);
    assertThat(i.locations()).isNull();
    assertThat(i.budgetMax()).isEqualTo(25000);
  }

  @Test
  void negation_excludesInsteadOfFiltersTo() {
    SearchIntent i = parser.parse("anywhere but andheri, budget 25k");

    assertThat(i.locations()).isNull();
    assertThat(i.excludeLocations()).extracting(SearchIntent.LocationRef::name)
        .containsExactlyInAnyOrder("Andheri East", "Andheri West");
    assertThat(i.budgetMax()).isEqualTo(25000);
  }

  @Test
  void theOriginalIntegrationQuery_stillParses() {
    SearchIntent i = parser.parse("Find me a room near BKC under 25k, no smokers");

    assertThat(i.searchTarget()).isEqualTo(SearchTarget.PROPERTIES);
    assertThat(i.budgetMax()).isEqualTo(25000);
    assertThat(i.commuteTo().place()).isEqualTo("BKC");
    assertThat(i.roomType()).isEqualTo(RoomType.PRIVATE);
    assertThat(i.lifestyle().smoking()).isEqualTo("NO_SMOKERS");
    assertThat(i.freeText()).isEqualTo("Find me a room near BKC under 25k, no smokers");
  }

  @Test
  void singleSharingIsStillPrivate_andHinglishStillWorks() {
    SearchIntent i = parser.parse("Single sharing room chahiye powai me budget 40k hai");

    assertThat(i.roomType()).isEqualTo(RoomType.PRIVATE);
    assertThat(i.locations()).extracting(SearchIntent.LocationRef::name).containsExactly("Powai");
    assertThat(i.budgetMax()).isEqualTo(40000);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=KeywordIntentParserTest 2>&1 | tail -40`
Expected: compiles (the constructor and `parse` exist) but several assertions FAIL — `floorCues_setBudgetMin_notMax` (budgetMax 30000 instead of budgetMin), `spelledOutAmounts_matchTheGlossary` (null), `verified_onlyWhenNotNegated` ("not verified" → true), `negation_excludesInsteadOfFiltersTo` (locations set), `ranges_setBoth`.

- [ ] **Step 3: Rewrite the parser**

Overwrite `backend/src/main/java/com/flatmaite/search/KeywordIntentParser.java`:

```java
package com.flatmaite.search;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.search.LocalityResolver.Match;
import com.flatmaite.search.SearchIntent.BhkRange;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.Lifestyle;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministic natural-language parser. Serves three roles: the keyless mock "LLM", the
 * degradation fallback when the provider misbehaves, and the reference the LLM's output is
 * grounded against. Amounts are classified by the words before them (floor vs ceiling vs deposit),
 * locality mentions by the words around them (home vs exclude vs commute).
 */
@Component
@RequiredArgsConstructor
public class KeywordIntentParser {

  private static final Pattern AMOUNT_K = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k\\b");
  private static final Pattern AMOUNT_LAKH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:lakhs?|lac|l)\\b");
  private static final Pattern AMOUNT_RUPEE = Pattern.compile("(?:rs\\.?|₹|inr)\\s*(\\d[\\d,]{3,8})\\b");
  private static final Pattern AMOUNT_PLAIN = Pattern.compile("\\b(\\d{4,7})\\b");
  private static final Pattern BHK = Pattern.compile("(\\d)\\s*bhk");
  private static final Pattern COMMUTE_MIN =
      Pattern.compile("(?:within|in|under|less than)\\s*(\\d{1,3})\\s*min(?:ute)?s?\\b");

  static final Set<String> FLOOR_CUES =
      Set.of("above", "over", "least", "minimum", "min", "from", "starting", "upwards");
  static final Set<String> CEILING_CUES =
      Set.of("under", "below", "upto", "max", "maximum", "within", "cap", "budget");
  /** Words that make a bare number read as money: every floor/ceiling cue plus these. */
  static final Set<String> MONEY_BEFORE =
      Set.of("for", "at", "around", "approx", "approximately", "rent", "than", "between", "rs", "inr", "price", "budget");
  static final Set<String> MONEY_AFTER =
      Set.of("rent", "rs", "rupees", "inr", "pm", "month", "monthly", "budget", "per", "p");
  private static final int CUE_WINDOW = 3;

  private final LocalityResolver localityResolver;

  /**
   * An amount in rupees with the character span it came from (in the lower-cased query).
   * {@code bare} = a plain 4–7 digit number with no k/lakh/currency marker — it only counts as money
   * when money words surround it (otherwise it is a pincode, an area, a year).
   */
  private record Amount(int value, int start, int end, boolean bare) {}

  public SearchIntent parse(String query) {
    String q = query.toLowerCase(Locale.ROOT);
    List<Tokens.Token> tokens = Tokens.of(query);

    // --- target ---
    SearchTarget target = SearchTarget.PROPERTIES;
    boolean mentionsPeople =
        q.contains("flatmate")
            || q.contains("roommate")
            || q.contains("room mate")
            || q.contains("someone")
            || q.contains("compatible with me")
            || q.contains("person to share");
    boolean wantsFlatmateWithFlat = q.contains("flatmate") && (q.contains("my flat") || q.contains("my place"));
    if (mentionsPeople && !wantsFlatmateWithFlat) {
      // "find a flatmate who ..." → people; "flat with people who don't smoke" → properties
      boolean flatWithPeople = q.matches(".*\\b(flat|room|apartment|bhk)s?\\b.*\\bwith\\b.*\\b(people|flatmates)\\b.*");
      target = flatWithPeople ? SearchTarget.PROPERTIES : SearchTarget.FLATMATES;
    }

    // --- amounts: floor / ceiling / range / deposit, decided by the words around each ---
    Integer budgetMin = null;
    Integer budgetMax = null;
    Integer maxDeposit = null;
    List<Amount> amounts = amounts(q);
    Set<Integer> usedDepositCues = new HashSet<>();
    for (int a = 0; a < amounts.size(); a++) {
      Amount amt = amounts.get(a);
      int ti = tokenIndexAt(tokens, amt.start());
      int afterTi = tokenIndexAt(tokens, amt.end());
      List<String> before = precedingWords(tokens, ti, CUE_WINDOW);
      Amount next = a + 1 < amounts.size() ? amounts.get(a + 1) : null;
      if (amt.bare() && !moneyContext(tokens, ti, afterTi, before, next)) {
        continue; // "pincode 400076", "1200 sqft": a number, not a budget
      }
      if (isDeposit(tokens, ti, afterTi, usedDepositCues)) {
        if (maxDeposit == null) {
          maxDeposit = amt.value();
        }
        continue;
      }
      if (next != null) {
        int nextTi = tokenIndexAt(tokens, next.start());
        if (isRange(tokens, afterTi, nextTi, before)) {
          budgetMin = Math.min(amt.value(), next.value());
          budgetMax = Math.max(amt.value(), next.value());
          a++;
          continue;
        }
      }
      if (isFloor(before)) {
        if (budgetMin == null) {
          budgetMin = amt.value();
        }
      } else if (budgetMax == null) {
        budgetMax = amt.value();
      }
    }

    // --- locations & commute, by the words around each mention ---
    LocationMentions mentions = LocationMentions.from(tokens, localityResolver.scan(query));
    List<LocationRef> locations = refs(mentions.home());
    List<LocationRef> excludeLocations = refs(mentions.exclude());
    CommuteTo commuteTo = null;
    if (mentions.commute().isPresent()) {
      Match anchor = mentions.commute().get();
      Integer maxMinutes = null;
      Matcher cm = COMMUTE_MIN.matcher(q);
      if (cm.find()) {
        maxMinutes = Integer.parseInt(cm.group(1));
      }
      UUID anchorId = anchor.localityIds().get(0);
      commuteTo =
          new CommuteTo(
              localityResolver.nameOf(anchorId),
              anchorId,
              maxMinutes == null ? SearchIntent.DEFAULT_COMMUTE_MINUTES : maxMinutes);
    }

    // --- room / property shape ---
    RoomType roomType;
    List<ListingType> listingTypes = null;
    BhkRange bhkRange = null;
    Matcher bhk = BHK.matcher(q);
    if (bhk.find()) {
      int n = Integer.parseInt(bhk.group(1));
      bhkRange = new BhkRange(n, n);
    }
    // occupancy vocabulary wins ("single sharing" is a PRIVATE room, not a shared one)
    roomType = RentalVocabulary.explicitRoomType(q);
    if (roomType != null) {
      // stated outright — no shape guessing needed
    } else if (q.contains("room") && !q.contains("bhk")) {
      roomType = RoomType.PRIVATE;
    } else if (bhkRange != null || q.contains("apartment") || q.contains("entire")) {
      // "2BHK where I can get a private room" stays private
      if (q.contains("private room")) {
        roomType = RoomType.PRIVATE;
      } else if (target == SearchTarget.PROPERTIES && !q.contains("flatmate")) {
        roomType = RoomType.ENTIRE;
      }
    }

    // --- furnishing ---
    Furnishing furnished = null;
    if (q.contains("semi furnished") || q.contains("semi-furnished")) {
      furnished = Furnishing.SEMI_FURNISHED;
    } else if (q.contains("unfurnished")) {
      furnished = Furnishing.UNFURNISHED;
    } else if (q.contains("furnished")) {
      furnished = Furnishing.FULLY_FURNISHED;
    }

    // --- lifestyle ---
    Boolean quiet =
        (q.contains("quiet") || q.contains("calm") || q.contains("peaceful") || q.contains("not a party")
                || q.contains("no party") || q.contains("don't want a party") || q.contains("not want a party"))
            ? true
            : null;
    String smoking = null;
    if (q.contains("non-smoker") || q.contains("non smoker") || q.contains("no smoking")
        || q.contains("don't smoke") || q.contains("doesn't smoke") || q.contains("dont smoke")
        || q.contains("doesnt smoke") || q.contains("no smokers") || q.contains("don't want smokers")
        || q.contains("without smokers")) {
      smoking = "NO_SMOKERS";
    }
    String pets = null;
    if (q.contains("no pets") || q.contains("without pets")) {
      pets = "NO_PETS";
    } else if (q.contains("pet friendly") || q.contains("pet-friendly") || q.contains("with my dog")
        || q.contains("with my cat") || q.contains("have a dog") || q.contains("have a cat")
        || q.contains("prefer pets") || q.contains("love pets")) {
      pets = "PET_FRIENDLY";
    }
    String diet = null;
    if (q.contains("vegetarian") || q.contains("veg only") || q.contains("pure veg")) {
      diet = "VEGETARIAN";
    }
    Boolean wfh = (q.contains("work from home") || q.contains("wfh")) ? true : null;
    Boolean partiesOk = null;
    if (q.contains("party house") || q.contains("parties frequently") || q.contains("throw parties")
        || q.contains("no parties") || q.contains("party people")) {
      partiesOk = false;
    }
    boolean drinkingNo = q.contains("don't drink") || q.contains("doesn't drink") || q.contains("no drinking");

    // --- gender ---
    GenderPreference gender = null;
    if (q.contains("female flatmate") || q.contains("girl flatmate") || q.contains("female only")
        || q.contains("girls only") || q.contains("for female") || q.contains("women only")) {
      gender = GenderPreference.FEMALE_ONLY;
    } else if (q.contains("male flatmate") || q.contains("male only") || q.contains("boys only")) {
      gender = GenderPreference.MALE_ONLY;
    }

    // --- misc ---
    Boolean verifiedOnly = verifiedOnly(tokens);
    String moveIn = null;
    if (q.contains("next month")) {
      moveIn = LocalDate.now().plusMonths(1).withDayOfMonth(1).toString();
    } else if (q.contains("immediately") || q.contains("asap") || q.contains("right away")) {
      moveIn = LocalDate.now().toString();
    }

    boolean lifestyleAny =
        quiet != null || smoking != null || pets != null || diet != null || wfh != null || partiesOk != null || drinkingNo;

    return SearchIntent.builder()
        .searchTarget(target)
        .locations(locations.isEmpty() ? null : locations)
        .excludeLocations(excludeLocations.isEmpty() ? null : excludeLocations)
        .budgetMin(budgetMin)
        .budgetMax(budgetMax)
        .maxDeposit(maxDeposit)
        .roomType(roomType)
        .listingTypes(listingTypes)
        .furnished(furnished)
        .bhk(bhkRange)
        .moveInDate(moveIn)
        .genderPreference(gender)
        .lifestyle(
            lifestyleAny
                ? Lifestyle.builder()
                    .quiet(quiet)
                    .smoking(smoking)
                    .pets(pets)
                    .diet(diet)
                    .wfh(wfh)
                    .partiesOk(partiesOk)
                    .drinking(drinkingNo ? "NO" : null)
                    .build()
                : null)
        .commuteTo(commuteTo)
        .verifiedOnly(verifiedOnly)
        .freeText(query)
        .originalQuery(query)
        .build();
  }

  // ------------------------------------------------------------------ amounts

  /** Every rupee amount in the text, in order, with spelled-out phrases included and overlaps dropped. */
  private static List<Amount> amounts(String q) {
    List<Amount> out = new ArrayList<>();
    collect(out, AMOUNT_LAKH.matcher(q), g -> (int) Math.round(Double.parseDouble(g) * 100_000), false);
    collect(out, AMOUNT_K.matcher(q), g -> (int) Math.round(Double.parseDouble(g) * 1_000), false);
    collect(out, AMOUNT_RUPEE.matcher(q), g -> Integer.parseInt(g.replace(",", "")), false);
    collect(out, AMOUNT_PLAIN.matcher(q), Integer::parseInt, true);
    Matcher words = NumberWords.NUMBER_RUN.matcher(q);
    while (words.find()) {
      OptionalInt value = NumberWords.parse(words.group());
      int end = words.start() + words.group().trim().length();
      if (value.isPresent() && value.getAsInt() >= 1_000 && !overlaps(out, words.start(), end)) {
        out.add(new Amount(value.getAsInt(), words.start(), end, false));
      }
    }
    out.sort((a, b) -> Integer.compare(a.start(), b.start()));
    return out;
  }

  private static void collect(
      List<Amount> out, Matcher m, java.util.function.ToIntFunction<String> toRupees, boolean bare) {
    while (m.find()) {
      if (!overlaps(out, m.start(), m.end())) {
        out.add(new Amount(toRupees.applyAsInt(m.group(1)), m.start(), m.end(), bare));
      }
    }
  }

  /** A bare number is money when a money cue precedes it, a money word follows it, or it opens a range. */
  private static boolean moneyContext(
      List<Tokens.Token> tokens, int ti, int afterTi, List<String> before, Amount next) {
    if (before.stream().anyMatch(w -> MONEY_BEFORE.contains(w) || FLOOR_CUES.contains(w) || CEILING_CUES.contains(w))) {
      return true;
    }
    if (afterTi < tokens.size() && (MONEY_AFTER.contains(tokens.get(afterTi).text()) || tokens.get(afterTi).text().equals("deposit"))) {
      return true;
    }
    return next != null && isRange(tokens, afterTi, tokenIndexAt(tokens, next.start()), before);
  }

  private static boolean overlaps(List<Amount> amounts, int start, int end) {
    for (Amount a : amounts) {
      if (start < a.end() && end > a.start()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFloor(List<String> before) {
    if (before.contains("not")) {
      return false; // "not more than 18k" is a ceiling
    }
    if (before.contains("more") || before.contains("greater") || before.contains("higher")) {
      return true;
    }
    return before.stream().anyMatch(FLOOR_CUES::contains);
  }

  /**
   * "deposit" right after the amount ("2 lakh deposit") or within the two words before it
   * ("deposit under 50k"). A cue already claimed by an earlier amount is not reused, so in
   * "2 lakh deposit, 30k rent" the 30k stays rent.
   */
  private static boolean isDeposit(List<Tokens.Token> tokens, int ti, int afterTi, Set<Integer> usedCues) {
    if (afterTi < tokens.size() && tokens.get(afterTi).text().equals("deposit") && usedCues.add(afterTi)) {
      return true;
    }
    for (int j = Math.max(0, ti - 2); j < ti; j++) {
      if (tokens.get(j).text().equals("deposit") && !usedCues.contains(j)) {
        usedCues.add(j);
        return true;
      }
    }
    return false;
  }

  /**
   * Two amounts joined by nothing ("25k-35k" — the dash vanishes in tokenisation), by "to", or by
   * "and" when "between" came before. {@code afterTi} is the first token after the first amount.
   */
  private static boolean isRange(List<Tokens.Token> tokens, int afterTi, int nextTi, List<String> before) {
    int gap = nextTi - afterTi;
    if (gap == 0) {
      return true;
    }
    if (gap == 1) {
      String joiner = tokens.get(afterTi).text();
      return joiner.equals("to") || (joiner.equals("and") && before.contains("between"));
    }
    return false;
  }

  // ------------------------------------------------------------------ words around a position

  private static int tokenIndexAt(List<Tokens.Token> tokens, int charStart) {
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).end() > charStart) {
        return i;
      }
    }
    return tokens.size();
  }

  private static List<String> precedingWords(List<Tokens.Token> tokens, int ti, int n) {
    List<String> out = new ArrayList<>();
    for (int i = Math.max(0, ti - n); i < ti && i < tokens.size(); i++) {
      out.add(tokens.get(i).text());
    }
    return out;
  }

  private static Boolean verifiedOnly(List<Tokens.Token> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).text().equals("verified")) {
        List<String> before = precedingWords(tokens, i, 2);
        if (!before.contains("not") && !before.contains("non") && !before.contains("un")) {
          return true;
        }
      }
    }
    return null;
  }

  private List<LocationRef> refs(List<Match> matches) {
    List<LocationRef> out = new ArrayList<>();
    for (Match m : matches) {
      for (UUID id : m.localityIds()) {
        if (out.stream().noneMatch(r -> id.equals(r.localityId()))) {
          out.add(new LocationRef(localityResolver.nameOf(id), id));
        }
      }
    }
    return out;
  }
}
```

- [ ] **Step 4: Run the parser test, then every pure suite**

Run: `./mvnw test -Dtest=KeywordIntentParserTest 2>&1 | tail -15`
Expected: 12 tests, `Failures: 0`.

Run: `./mvnw test -Dtest='KeywordIntentParserTest,NewQueryDetectorTest,MockIntentLlmTest,RentalVocabularyTest,LocalityResolverTest,LocationMentionsTest,NumberWordsTest' 2>&1 | tail -20`
Expected: all green — the detector's 13 cases and the mock LLM's 5 cases still pass on the new parser.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/flatmaite/search/KeywordIntentParser.java src/test/java/com/flatmaite/search/KeywordIntentParserTest.java
git commit -m "$(cat <<'EOF'
Parse budget floors, ranges and spelled-out amounts; read negation around places and "verified"

"more than 30000" is a floor, "between 20k and 30k" is a range, "twenty five
thousand" is 25000, "not verified" sets nothing, and "anywhere but Andheri"
excludes instead of filtering to it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: One arbiter — `NewQueryDetector.decide`, `IntentLlm.Extraction`, `RefineResult`, controller flow

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/NewQueryDetector.java` (full rewrite)
- Modify: `backend/src/main/java/com/flatmaite/ai/IntentLlm.java` — `Mode`, `Extraction`, `extractWithMode`
- Create: `backend/src/main/java/com/flatmaite/ai/RefineResult.java`
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` — `extractIntent` returns `Extraction`
- Modify: `backend/src/main/java/com/flatmaite/search/AiSearchController.java` — verdict flow
- Test: `backend/src/test/java/com/flatmaite/search/NewQueryDetectorTest.java` (extended), `backend/src/test/java/com/flatmaite/ai/IntentLlmModeTest.java` (new), `backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java` (two tests added)

**Interfaces:**
- Consumes: `LocalityResolver.scan`, `LocalityResolver.CONFIDENT` (Task 2).
- Produces: `NewQueryDetector.Verdict { NEW, REFINE, AMBIGUOUS }`, `Verdict decide(String query)`, `boolean isSelfContained(String query)` (= `decide == NEW`); `IntentLlm.Mode { NEW, REFINE, UNSURE, NONE }` with `static Mode parse(String raw)`; `IntentLlm.Extraction(SearchIntent intent, Mode mode)`; `default Extraction extractWithMode(String query, SearchIntent prior)` (mock → `NONE`); `RefineResult(SearchIntent intent, String mode)`; `SearchPipeline.extractIntent(...) : IntentLlm.Extraction`.

- [ ] **Step 1: Write the failing tests**

Append to `backend/src/test/java/com/flatmaite/search/NewQueryDetectorTest.java` (inside the class, after the existing tests):

```java
  @Test
  void freshCue_isNew_evenWithOneAnchor() {
    assertThat(detector.decide("forget that, show me flats in powai")).isEqualTo(NewQueryDetector.Verdict.NEW);
    assertThat(detector.decide("start over: 1bhk near bkc")).isEqualTo(NewQueryDetector.Verdict.NEW);
  }

  @Test
  void refinementCue_keepsTwoAnchorsAsARefinement() {
    assertThat(detector.decide("actually make it andheri and 30k")).isEqualTo(NewQueryDetector.Verdict.REFINE);
    assertThat(detector.decide("same but in powai for 25k")).isEqualTo(NewQueryDetector.Verdict.REFINE);
  }

  @Test
  void threeAnchors_areNew_evenWithACueWord() {
    assertThat(detector.decide("only single sharing room in powai 40k")).isEqualTo(NewQueryDetector.Verdict.NEW);
  }

  @Test
  void oneAnchorWithANoun_isAmbiguous() {
    assertThat(detector.decide("sea view flat in powai")).isEqualTo(NewQueryDetector.Verdict.AMBIGUOUS);
    assertThat(detector.decide("flats in goregaon")).isEqualTo(NewQueryDetector.Verdict.AMBIGUOUS);
  }

  @Test
  void fuzzyLocality_doesNotCountAsAnAnchor() {
    // "powaii" resolves fuzzily below CONFIDENT → 0 anchors → refinement
    assertThat(detector.decide("flats in powaii")).isEqualTo(NewQueryDetector.Verdict.REFINE);
  }

  @Test
  void blank_isARefinement() {
    assertThat(detector.decide("")).isEqualTo(NewQueryDetector.Verdict.REFINE);
    assertThat(detector.decide(null)).isEqualTo(NewQueryDetector.Verdict.REFINE);
  }
```

and add `import org.junit.jupiter.api.Test;` to that file's imports.

Create `backend/src/test/java/com/flatmaite/ai/IntentLlmModeTest.java`:

```java
package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.LocalityResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentLlmModeTest {

  @Test
  void parse_isLenient() {
    assertThat(IntentLlm.Mode.parse("NEW")).isEqualTo(IntentLlm.Mode.NEW);
    assertThat(IntentLlm.Mode.parse(" refine ")).isEqualTo(IntentLlm.Mode.REFINE);
    assertThat(IntentLlm.Mode.parse("UNSURE")).isEqualTo(IntentLlm.Mode.UNSURE);
    assertThat(IntentLlm.Mode.parse("banana")).isEqualTo(IntentLlm.Mode.UNSURE);
    assertThat(IntentLlm.Mode.parse(null)).isEqualTo(IntentLlm.Mode.UNSURE);
  }

  @Test
  void mockLlm_neverOffersAnOpinion() {
    LocalityResolver resolver = mock(LocalityResolver.class);
    when(resolver.scan(anyString())).thenReturn(List.of());
    MockLlms.MockIntentLlm llm = new MockLlms.MockIntentLlm(new KeywordIntentParser(resolver));

    IntentLlm.Extraction first = llm.extractWithMode("room under 25k", null);
    IntentLlm.Extraction refined = llm.extractWithMode("with a balcony", first.intent());

    assertThat(first.mode()).isEqualTo(IntentLlm.Mode.NONE);
    assertThat(refined.mode()).isEqualTo(IntentLlm.Mode.NONE);
    assertThat(refined.intent().budgetMax()).isEqualTo(25000);
  }

  @Test
  void refineResult_parsesWithAndWithoutMode() throws Exception {
    ObjectMapper om = new ObjectMapper();

    RefineResult withMode =
        om.readValue("{\"intent\":{\"budgetMax\":20000},\"mode\":\"REFINE\",\"extra\":1}", RefineResult.class);
    RefineResult withoutMode = om.readValue("{\"intent\":{\"budgetMax\":20000}}", RefineResult.class);

    assertThat(withMode.intent().budgetMax()).isEqualTo(20000);
    assertThat(IntentLlm.Mode.parse(withMode.mode())).isEqualTo(IntentLlm.Mode.REFINE);
    assertThat(IntentLlm.Mode.parse(withoutMode.mode())).isEqualTo(IntentLlm.Mode.UNSURE);
  }
}
```

Add to `backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java` after `flatmateSearch_returnsPeople`:

```java
  @Test
  @Order(4)
  @SuppressWarnings("unchecked")
  void ambiguousFollowUp_staysARefinement_whenTheModelHasNoOpinion() {
    // one anchor + a housing noun → AMBIGUOUS; the mock offers no mode → the detector's default (REFINE)
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, anonCookie);
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/refine",
            new HttpEntity<>(Map.of("query", "flats in powai", "sessionId", sessionId), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    // budget from the earlier turns survives, the locality is added
    assertThat((Integer) intent.get("budgetMax")).isLessThan(25000);
    List<Map<String, Object>> locations = (List<Map<String, Object>>) intent.get("locations");
    assertThat(locations).extracting(l -> l.get("name")).contains("Powai");
    assertThat(data.get("note")).isNull();
  }

  @Test
  @Order(5)
  @SuppressWarnings("unchecked")
  void freshCue_startsOver_andSaysSo() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, anonCookie);
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/ai/refine",
            new HttpEntity<>(
                Map.of("query", "forget that, single sharing room in goregaon 20k", "sessionId", sessionId), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    Map<String, Object> intent = (Map<String, Object>) data.get("intent");
    assertThat(intent.get("budgetMax")).isEqualTo(20000);
    assertThat(intent.get("commuteTo")).isNull();
    assertThat((String) data.get("note")).contains("fresh search");
  }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest='NewQueryDetectorTest,IntentLlmModeTest' 2>&1 | tail -20`
Expected: `COMPILATION ERROR` — `Verdict`, `decide`, `Mode`, `Extraction`, `RefineResult` not found.

- [ ] **Step 3: Rewrite `NewQueryDetector`**

Overwrite `backend/src/main/java/com/flatmaite/search/NewQueryDetector.java`:

```java
package com.flatmaite.search;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one referee for "is this follow-up a new search or a tweak of the current one". Getting it
 * wrong one way strands users on constraints they never asked for; the other way wipes out what
 * they built up. Lexical rules decide the clear cases; the controller consults the model's opinion
 * only for {@link Verdict#AMBIGUOUS}.
 */
@Component
@RequiredArgsConstructor
public class NewQueryDetector {

  public enum Verdict {
    NEW,
    REFINE,
    AMBIGUOUS
  }

  private static final Pattern FRESH_CUE =
      Pattern.compile("\\b(forget that|forget it|start over|new search|scrap that|from scratch)\\b");
  private static final Pattern REFINEMENT_CUE =
      Pattern.compile(
          "\\b(make it|instead|also|actually|same but|but in|rather|change it|change the|only|cheaper|closer|nearer)\\b");
  private static final Pattern BUDGET =
      Pattern.compile("(\\d+(?:\\.\\d+)?\\s*k\\b)|(\\d{4,7})|(\\d+(?:\\.\\d+)?\\s*(?:lakh|lac)\\b)");
  private static final Pattern BHK = Pattern.compile("\\d\\s*bhk");
  private static final Pattern HOUSING_NOUN =
      Pattern.compile("\\b(flatmate|roommate|pg|paying guest|apartment|flat|flats|room|rooms|studio|1rk)\\b");

  private final LocalityResolver localityResolver;

  public Verdict decide(String query) {
    if (query == null || query.isBlank()) {
      return Verdict.REFINE;
    }
    String q = query.toLowerCase(Locale.ROOT);
    if (FRESH_CUE.matcher(q).find()) {
      return Verdict.NEW;
    }
    int anchors = anchors(q);
    if (anchors >= 3) {
      return Verdict.NEW;
    }
    if (REFINEMENT_CUE.matcher(q).find()) {
      return Verdict.REFINE;
    }
    if (anchors >= 2 && HOUSING_NOUN.matcher(q).find()) {
      return Verdict.NEW;
    }
    if (anchors == 0) {
      return Verdict.REFINE;
    }
    return Verdict.AMBIGUOUS;
  }

  /** A request the detector alone rules NEW. */
  public boolean isSelfContained(String query) {
    return decide(query) == Verdict.NEW;
  }

  /** Independent anchors: a confidently recognised locality, a budget, an occupancy word, a BHK. */
  private int anchors(String q) {
    int anchors = 0;
    boolean confidentLocality =
        localityResolver.scan(q).stream().anyMatch(m -> m.confidence() >= LocalityResolver.CONFIDENT);
    if (confidentLocality) {
      anchors++;
    }
    if (BUDGET.matcher(q).find()) {
      anchors++;
    }
    if (RentalVocabulary.explicitRoomType(q) != null) {
      anchors++;
    }
    if (BHK.matcher(q).find()) {
      anchors++;
    }
    return anchors;
  }
}
```

- [ ] **Step 4: `IntentLlm` and `RefineResult`**

Overwrite `backend/src/main/java/com/flatmaite/ai/IntentLlm.java`:

```java
package com.flatmaite.ai;

import com.flatmaite.search.SearchIntent;
import java.util.Locale;

/**
 * LLM abstraction for intent extraction. The provider impl uses structured outputs; the mock impl
 * delegates to the deterministic keyword parser so everything works key-free.
 */
public interface IntentLlm {

  /** The model's opinion on a follow-up. NONE = no opinion offered (mock, heuristics, first turn). */
  enum Mode {
    NEW,
    REFINE,
    UNSURE,
    NONE;

    /** Lenient read of the model's report; anything unexpected is UNSURE. */
    public static Mode parse(String raw) {
      if (raw == null) {
        return UNSURE;
      }
      return switch (raw.trim().toUpperCase(Locale.ROOT)) {
        case "NEW" -> NEW;
        case "REFINE" -> REFINE;
        default -> UNSURE;
      };
    }
  }

  record Extraction(SearchIntent intent, Mode mode) {}

  /**
   * Extracts the FULL updated intent. When {@code prior} is non-null this is a conversational
   * refinement: apply the user's modification on top of the prior intent.
   */
  SearchIntent extract(String query, SearchIntent prior);

  /** The intent plus the model's opinion on whether the follow-up was a new search. */
  default Extraction extractWithMode(String query, SearchIntent prior) {
    return new Extraction(extract(query, prior), Mode.NONE);
  }

  /**
   * Minimal round-trip that must propagate provider errors — {@link #extract} deliberately swallows
   * them to fall back, which hides a dead key or retired model. Mock impls stay no-ops.
   */
  default void healthCheck() {}

  String providerName();

  String model();
}
```

Create `backend/src/main/java/com/flatmaite/ai/RefineResult.java`:

```java
package com.flatmaite.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flatmaite.search.SearchIntent;

/** What the refine prompt returns: the merged intent plus the model's read of the follow-up. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefineResult(SearchIntent intent, String mode) {}
```

- [ ] **Step 5: `SearchPipeline.extractIntent` returns an `Extraction`**

In `backend/src/main/java/com/flatmaite/search/SearchPipeline.java`:

(a) Change the cache field to `private final Cache<String, IntentLlm.Extraction> intentCache = Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(Duration.ofHours(24)).build();`

(b) Replace the whole `extractIntent` method with:

```java
  public IntentLlm.Extraction extractIntent(String query, SearchIntent prior, UUID userId, String anonKey) {
    long start = System.currentTimeMillis();
    AiFeature feature = prior == null ? AiFeature.INTENT_EXTRACTION : AiFeature.REFINEMENT;

    // 1) zero-cost heuristic refinements
    SearchIntent heuristic = RefinementHeuristics.apply(prior, query);
    if (heuristic != null) {
      usageService.log(userId, anonKey, feature, "heuristic", "regex", 0, 0, true, true,
          System.currentTimeMillis() - start, null);
      return new IntentLlm.Extraction(resolveLocalities(heuristic), IntentLlm.Mode.NONE);
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
        new IntentLlm.Extraction(resolveLocalities(extracted.intent()), extracted.mode());
    intentCache.put(cacheKey, resolved);
    usageService.log(
        userId,
        anonKey,
        feature,
        intentLlm.providerName(),
        intentLlm.model(),
        AiUsageService.estimateTokens(query) + 700,
        AiUsageService.estimateTokens(intentJson(resolved.intent())),
        false,
        success,
        System.currentTimeMillis() - start,
        cacheKey);
    return resolved;
  }
```

(`resolveLocalities` itself is rewritten in Task 7; it keeps its `SearchIntent → SearchIntent` signature.) Add `import com.flatmaite.ai.IntentLlm;` if not already present (it is — `IntentLlm` is already injected).

- [ ] **Step 6: Controller flow**

In `backend/src/main/java/com/flatmaite/search/AiSearchController.java`, add the constant after `ANON_COOKIE`:

```java
  private static final String FRESH_NOTE =
      "Started a fresh search — this read as a new request, not a tweak of the last one.";
```

and replace, inside `search(...)`, everything from the comment `// A complete new request must not inherit ...` through `SearchIntent intent = pipeline.extractIntent(body.query(), prior, userId, anonKey);` with:

```java
    // One referee: the detector rules the clear cases; only for an ambiguous follow-up does the
    // model's read of the message (its `mode`) break the tie. A new request must not inherit the
    // previous search's constraints — a stale locality silently zeroes out results.
    NewQueryDetector.Verdict verdict =
        prior == null ? NewQueryDetector.Verdict.NEW : newQueryDetector.decide(body.query());
    String note = null;
    if (prior != null && verdict == NewQueryDetector.Verdict.NEW) {
      prior = null;
      note = FRESH_NOTE;
    }
    IntentLlm.Extraction extraction = pipeline.extractIntent(body.query(), prior, userId, anonKey);
    if (prior != null
        && verdict == NewQueryDetector.Verdict.AMBIGUOUS
        && extraction.mode() == IntentLlm.Mode.NEW) {
      prior = null;
      note = FRESH_NOTE;
      extraction = pipeline.extractIntent(body.query(), null, userId, anonKey);
    }
    SearchIntent intent = extraction.intent();
```

Add `import com.flatmaite.ai.IntentLlm;`.

- [ ] **Step 7: Run the unit tests, then the pipeline integration test (Docker)**

Run: `./mvnw test -Dtest='NewQueryDetectorTest,IntentLlmModeTest,MockIntentLlmTest' 2>&1 | tail -15`
Expected: `NewQueryDetectorTest` 19 (13 parameterised executions + 6 new), `IntentLlmModeTest` 3, `MockIntentLlmTest` 5; `Failures: 0`.

Run: `./mvnw test -Dtest=SearchPipelineIntegrationTest 2>&1 | tail -20`
Expected: 5 tests, `Failures: 0`, `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/flatmaite/search/NewQueryDetector.java src/main/java/com/flatmaite/ai/IntentLlm.java src/main/java/com/flatmaite/ai/RefineResult.java src/main/java/com/flatmaite/search/SearchPipeline.java src/main/java/com/flatmaite/search/AiSearchController.java src/test/java/com/flatmaite/search/NewQueryDetectorTest.java src/test/java/com/flatmaite/ai/IntentLlmModeTest.java src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java
git commit -m "$(cat <<'EOF'
Make the controller the one new-vs-refine referee, with the model as tie-breaker

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Prompts with vocabulary and examples, merge-only refine with a mode report, temperature 0, unresolved-name handling

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/ai/OpenAiLlms.java` — prompt builders, `OpenAiIntentLlm` constructor + `extractWithMode`
- Modify: `backend/src/main/java/com/flatmaite/ai/AiProviderConfig.java` — pass `LocalityResolver`
- Modify: `backend/src/main/resources/application.yml:34,44` — temperature `0.2` → `0`
- Create: `backend/src/main/java/com/flatmaite/search/IntentLocalities.java`
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` — `resolveLocalities` delegates to `IntentLocalities`, logs new unresolved names, drops the temporary `firstId`
- Test: `backend/src/test/java/com/flatmaite/ai/OpenAiLlmsPromptTest.java` (new), `backend/src/test/java/com/flatmaite/search/IntentLocalitiesTest.java` (new)

**Interfaces:**
- Consumes: `LocalityResolver.vocabulary()`, `resolve`, `nameOf` (Task 2); `IntentLlm.Extraction/Mode`, `RefineResult` (Task 6); `SearchIntent.joinFreeText` (WS1).
- Produces: package-private `static String OpenAiLlms.intentSystem(List<String> vocabulary)`, `static final String OpenAiLlms.REFINE_SYSTEM`, `static String OpenAiLlms.refineUserMessage(String priorJson, String query)`; `OpenAiIntentLlm(ChatClient, KeywordIntentParser, ObjectMapper, String modelName, String providerName, LocalityResolver)`; `static SearchIntent IntentLocalities.resolve(SearchIntent intent, LocalityResolver resolver)`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/flatmaite/ai/OpenAiLlmsPromptTest.java`:

```java
package com.flatmaite.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiLlmsPromptTest {

  @Test
  void intentSystem_carriesTheVocabularyTheRulesAndTheExamples() {
    String s = OpenAiLlms.intentSystem(List.of("Powai (hiranandani, iit bombay)", "BKC (bandra kurla complex)", "Kurla"));

    assertThat(s).contains("Powai (hiranandani, iit bombay)", "BKC (bandra kurla complex)", "Kurla");
    assertThat(s).contains("excludeLocations", "budgetMin", "single sharing");
    assertThat(s.chars().filter(ch -> ch == '→').count()).isGreaterThanOrEqualTo(9);
    assertThat(s).doesNotContain("REPLACE");
  }

  @Test
  void refineSystem_mergesOnly_andAsksForAMode() {
    assertThat(OpenAiLlms.REFINE_SYSTEM).doesNotContain("REPLACE");
    assertThat(OpenAiLlms.REFINE_SYSTEM).contains("FULL merged intent", "mode", "NEW", "REFINE", "UNSURE");
    // no format placeholders left: the prior intent no longer travels in the system message
    assertThat(OpenAiLlms.REFINE_SYSTEM).doesNotContain("%s");
  }

  @Test
  void refineUserMessage_carriesPriorIntentAndFollowUp() {
    String m = OpenAiLlms.refineUserMessage("{\"budgetMax\":25000}", "with a balcony");

    assertThat(m).contains("Current intent:", "{\"budgetMax\":25000}", "Follow-up:", "with a balcony");
  }
}
```

Create `backend/src/test/java/com/flatmaite/search/IntentLocalitiesTest.java`:

```java
package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IntentLocalitiesTest {

  private LocalityResolver resolver;

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes());
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", "hiranandani"),
                locality("Andheri East", "andheri"),
                locality("Andheri West", "andheri"),
                locality("BKC", "bandra kurla complex", "bandra kurla")));
    resolver = new LocalityResolver(repo);
    resolver.load();
  }

  @Test
  void unknownName_movesToUnresolved_andIntoFreeText() {
    SearchIntent in =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Atlantis", null)))
            .freeText("room in atlantis")
            .originalQuery("room in Atlantis")
            .build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.locations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Atlantis");
    assertThat(out.freeText()).isEqualTo("room in atlantis"); // already mentioned — not appended twice
  }

  @Test
  void unknownName_isAppendedToFreeText_whenAbsent() {
    SearchIntent in =
        SearchIntent.builder().locations(List.of(new LocationRef("Atlantis", null))).freeText("quiet room").build();

    assertThat(IntentLocalities.resolve(in, resolver).freeText()).isEqualTo("quiet room Atlantis");
  }

  @Test
  void ambiguousAlias_expandsToEveryLocality() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("andheri", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.locations()).extracting(LocationRef::localityId).containsExactlyInAnyOrder(id("Andheri East"), id("Andheri West"));
    assertThat(out.locations()).extracting(LocationRef::name).containsExactlyInAnyOrder("Andheri East", "Andheri West");
  }

  @Test
  void alreadyResolvedRef_keepsItsIdAndName() {
    SearchIntent in = SearchIntent.builder().locations(List.of(new LocationRef("My Powai", id("Powai")))).build();

    assertThat(IntentLocalities.resolve(in, resolver).locations()).containsExactly(new LocationRef("My Powai", id("Powai")));
  }

  @Test
  void commutePlace_isCanonicalised_orMarkedUnresolved() {
    SearchIntent known = SearchIntent.builder().commuteTo(new CommuteTo("bandra kurla", null, 20)).build();
    SearchIntent unknown = SearchIntent.builder().commuteTo(new CommuteTo("Nowhere", null, 20)).build();

    CommuteTo resolved = IntentLocalities.resolve(known, resolver).commuteTo();
    assertThat(resolved.localityId()).isEqualTo(id("BKC"));
    assertThat(resolved.place()).isEqualTo("BKC");
    assertThat(resolved.maxMinutes()).isEqualTo(20);

    SearchIntent out = IntentLocalities.resolve(unknown, resolver);
    assertThat(out.commuteTo().localityId()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Nowhere");
  }

  @Test
  void excludedUnknownName_isSurfacedToo() {
    SearchIntent in = SearchIntent.builder().excludeLocations(List.of(new LocationRef("Atlantis", null))).build();

    SearchIntent out = IntentLocalities.resolve(in, resolver);

    assertThat(out.excludeLocations()).isNull();
    assertThat(out.unresolvedLocations()).containsExactly("Atlantis");
  }

  @Test
  void nothingToResolve_returnsTheSameIntent() {
    SearchIntent in = SearchIntent.builder().budgetMax(20000).build();

    assertThat(IntentLocalities.resolve(in, resolver)).isSameAs(in);
  }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest='OpenAiLlmsPromptTest,IntentLocalitiesTest' 2>&1 | tail -20`
Expected: `COMPILATION ERROR` — `intentSystem`, `refineUserMessage`, `IntentLocalities` not found.

- [ ] **Step 3: `IntentLocalities`**

Create `backend/src/main/java/com/flatmaite/search/IntentLocalities.java`:

```java
package com.flatmaite.search;

import com.flatmaite.search.LocalityResolver.Match;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Binds the names an intent carries (from the LLM, the parser or a chip edit) to locality ids.
 * An ambiguous alias expands to one ref per locality; a name no layer can place moves to
 * {@code unresolvedLocations} and stays in {@code freeText} so lexical and semantic retrieval
 * still see it. Never guesses by substring.
 */
final class IntentLocalities {

  private IntentLocalities() {}

  static SearchIntent resolve(SearchIntent intent, LocalityResolver resolver) {
    if (intent.locations() == null && intent.excludeLocations() == null && intent.commuteTo() == null) {
      return intent;
    }
    List<String> unresolved =
        new ArrayList<>(intent.unresolvedLocations() == null ? List.of() : intent.unresolvedLocations());
    List<LocationRef> home = resolveRefs(intent.locations(), resolver, unresolved);
    List<LocationRef> exclude = resolveRefs(intent.excludeLocations(), resolver, unresolved);

    CommuteTo commute = intent.commuteTo();
    if (commute != null && commute.localityId() == null) {
      Optional<Match> m = resolver.resolve(commute.place());
      if (m.isPresent()) {
        UUID anchor = m.get().localityIds().get(0);
        commute = new CommuteTo(resolver.nameOf(anchor), anchor, commute.maxMinutes());
      } else if (commute.place() != null && !unresolved.contains(commute.place())) {
        unresolved.add(commute.place());
      }
    }

    String freeText = intent.freeText();
    for (String name : unresolved) {
      if (freeText == null || !freeText.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
        freeText = SearchIntent.joinFreeText(freeText, name);
      }
    }

    return intent.toBuilder()
        .locations(home.isEmpty() ? null : home)
        .excludeLocations(exclude.isEmpty() ? null : exclude)
        .commuteTo(commute)
        .unresolvedLocations(unresolved.isEmpty() ? null : unresolved)
        .freeText(freeText)
        .build();
  }

  private static List<LocationRef> resolveRefs(List<LocationRef> refs, LocalityResolver resolver, List<String> unresolved) {
    List<LocationRef> out = new ArrayList<>();
    if (refs == null) {
      return out;
    }
    for (LocationRef ref : refs) {
      if (ref.localityId() != null) {
        out.add(ref);
        continue;
      }
      Optional<Match> m = resolver.resolve(ref.name());
      if (m.isEmpty()) {
        if (ref.name() != null && !unresolved.contains(ref.name())) {
          unresolved.add(ref.name());
        }
        continue;
      }
      for (UUID id : m.get().localityIds()) {
        if (out.stream().noneMatch(r -> id.equals(r.localityId()))) {
          out.add(new LocationRef(resolver.nameOf(id), id));
        }
      }
    }
    return out;
  }
}
```

In `backend/src/main/java/com/flatmaite/search/SearchPipeline.java`, replace the whole `resolveLocalities` method (and delete the Task-2 `firstId` helper) with:

```java
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
```

- [ ] **Step 4: Prompts and the provider implementation**

In `backend/src/main/java/com/flatmaite/ai/OpenAiLlms.java`:

(a) Replace the `INTENT_SYSTEM` and `REFINE_SYSTEM` constants with:

```java
  static final String INTENT_RULES =
      """
      You convert flat/flatmate search queries for Mumbai into a structured SearchIntent JSON.
      Rules:
      - Only extract what the user actually said. Unknown fields stay null. Never invent budgets or places.
      - Amounts: "25k" = 25000 rupees/month, "1 lakh" = 100000. budgetMax for ceilings ("under 25k"),
        budgetMin for floors ("more than 30000", "at least 20k"), both for ranges ("between 20k and 30k").
      - searchTarget: PROPERTIES for rooms/flats, FLATMATES when they look for a person, BOTH when genuinely both.
      - locations: where they want to live. excludeLocations: places to avoid ("anywhere but Andheri", "not in Bandra").
        Do not guess ids; leave localityId null.
      - commuteTo: set when they mention working somewhere or wanting to be near/within X minutes of a place.
      - lifestyle.smoking: NO_SMOKERS when they don't want smokers. lifestyle.quiet: true when they want a calm/quiet home or no party house.
      - freeText: any residual nuance not captured by structured fields.
      """
          + com.flatmaite.search.RentalVocabulary.GLOSSARY;

  static final String EXAMPLES =
      """
      Examples (query → JSON, abbreviated to the fields that matter):
      "single sharing room in powai under 25k" → {"searchTarget":"PROPERTIES","locations":[{"name":"Powai"}],"budgetMax":25000,"roomType":"PRIVATE"}
      "Single sharing room chahiye powai me budget 40k hai" → {"searchTarget":"PROPERTIES","locations":[{"name":"Powai"}],"budgetMax":40000,"roomType":"PRIVATE"}
      "anywhere but Andheri, budget 25k" → {"searchTarget":"PROPERTIES","excludeLocations":[{"name":"Andheri East"},{"name":"Andheri West"}],"budgetMax":25000}
      "room in Andheri, I work at BKC" → {"searchTarget":"PROPERTIES","locations":[{"name":"Andheri East"},{"name":"Andheri West"}],"commuteTo":{"place":"BKC","maxMinutes":30},"roomType":"PRIVATE"}
      "flat for twenty five thousand in Malad" → {"searchTarget":"PROPERTIES","locations":[{"name":"Malad"}],"budgetMax":25000}
      "2bhk in Goregaon, more than 30000" → {"searchTarget":"PROPERTIES","locations":[{"name":"Goregaon"}],"bhk":{"min":2,"max":2},"budgetMin":30000,"roomType":"ENTIRE"}
      "between 20k and 30k near Dadar" → {"searchTarget":"PROPERTIES","commuteTo":{"place":"Dadar","maxMinutes":30},"budgetMin":20000,"budgetMax":30000}
      "female flatmate, no pets, quiet" → {"searchTarget":"FLATMATES","genderPreference":"FEMALE_ONLY","lifestyle":{"pets":"NO_PETS","quiet":true}}
      "1bhk near Hiranandani Gardens" → {"searchTarget":"PROPERTIES","commuteTo":{"place":"Powai","maxMinutes":30},"bhk":{"min":1,"max":1},"roomType":"ENTIRE"}
      """;

  /** Rules + glossary + the controlled locality vocabulary + worked examples. Built once per bean. */
  static String intentSystem(List<String> vocabulary) {
    return INTENT_RULES
        + "\nKnown localities — use these canonical names in locations, excludeLocations and commuteTo; map"
        + " landmarks and aliases onto them; a place not in this list goes into locations exactly as the"
        + " user wrote it:\n"
        + String.join("\n", vocabulary)
        + "\n\n"
        + EXAMPLES;
  }

  static final String REFINE_SYSTEM =
      """
      You update an existing SearchIntent JSON given the user's follow-up message.
      Apply the follow-up to the current intent and return the FULL merged intent — never drop
      fields the user did not change. "cheaper" reduces budgetMax ~10%. "closer" tightens
      commuteTo.maxMinutes ~20%. Unknown fields stay null.
      Also report mode: NEW if the follow-up reads as a complete request on its own, REFINE if it
      adjusts the current search, UNSURE otherwise. The caller decides what to do with it — you
      always merge.
      """;

  /** The prior intent contains the user's own words, so it travels in the user role, never the system role. */
  static String refineUserMessage(String priorJson, String query) {
    return "Current intent:\n" + priorJson + "\n\nFollow-up:\n" + query;
  }
```

(b) Replace the `OpenAiIntentLlm` class header, fields and `extract`/`call` methods (keep `healthCheck`, `finish`, `providerName`, `model`) with:

```java
  @Slf4j
  public static class OpenAiIntentLlm implements IntentLlm {

    private final ChatClient chatClient;
    private final KeywordIntentParser fallback;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final String providerName;
    private final String intentSystem;

    public OpenAiIntentLlm(
        ChatClient chatClient,
        KeywordIntentParser fallback,
        ObjectMapper objectMapper,
        String modelName,
        String providerName,
        com.flatmaite.search.LocalityResolver localityResolver) {
      this.chatClient = chatClient;
      this.fallback = fallback;
      this.objectMapper = objectMapper;
      this.modelName = modelName;
      this.providerName = providerName;
      this.intentSystem = intentSystem(localityResolver.vocabulary());
    }

    @Override
    public void healthCheck() {
      chatClient.prompt().user("Reply with the single word OK").call().content();
    }

    @Override
    public SearchIntent extract(String query, SearchIntent prior) {
      return extractWithMode(query, prior).intent();
    }

    @Override
    public Extraction extractWithMode(String query, SearchIntent prior) {
      if (prior == null) {
        BeanOutputConverter<SearchIntent> converter = new BeanOutputConverter<>(SearchIntent.class);
        try {
          return new Extraction(finish(call(intentSystem, query, converter, null), query, null), Mode.NONE);
        } catch (Exception first) {
          log.warn("Intent extraction failed, attempting repair: {}", first.getMessage());
          try {
            return new Extraction(finish(call(intentSystem, query, converter, first.getMessage()), query, null), Mode.NONE);
          } catch (Exception second) {
            log.warn("Intent repair failed, using keyword fallback: {}", second.getMessage());
            return new Extraction(fallback.parse(query), Mode.NONE);
          }
        }
      }
      BeanOutputConverter<RefineResult> converter = new BeanOutputConverter<>(RefineResult.class);
      String priorJson;
      try {
        priorJson = objectMapper.writeValueAsString(prior);
      } catch (Exception e) {
        priorJson = "{}";
      }
      String user = refineUserMessage(priorJson, query);
      try {
        RefineResult result = call(REFINE_SYSTEM, user, converter, null);
        return new Extraction(finish(result.intent(), query, prior), Mode.parse(result.mode()));
      } catch (Exception first) {
        log.warn("Intent refinement failed, attempting repair: {}", first.getMessage());
        try {
          RefineResult result = call(REFINE_SYSTEM, user, converter, first.getMessage());
          return new Extraction(finish(result.intent(), query, prior), Mode.parse(result.mode()));
        } catch (Exception second) {
          log.warn("Intent refinement repair failed, using keyword merge: {}", second.getMessage());
          return new Extraction(new MockLlms.MockIntentLlm(fallback).extract(query, prior), Mode.NONE);
        }
      }
    }

    private <T> T call(String system, String user, BeanOutputConverter<T> converter, String repairHint) {
      String content =
          repairHint == null
              ? user
              : user + "\n\n(Your previous output was invalid: " + repairHint + ". Return valid JSON only.)";
      String raw =
          chatClient
              .prompt()
              .system(system + "\n" + converter.getFormat())
              .user(content)
              .call()
              .content();
      T parsed = converter.convert(raw);
      if (parsed == null) {
        throw new IllegalStateException("Converter returned null");
      }
      return parsed;
    }
```

Remove the class-level `@RequiredArgsConstructor` from `OpenAiIntentLlm` (the explicit constructor replaces it) and remove the now-unused `import lombok.RequiredArgsConstructor;` only if `OpenAiExplainerLlm` no longer uses it (it does — keep the import). `finish(...)` is unchanged from WS1.

(c) `backend/src/main/java/com/flatmaite/ai/AiProviderConfig.java` — add a parameter `com.flatmaite.search.LocalityResolver localityResolver,` to the `intentLlm(...)` bean method (after `keywordParser`) and pass `localityResolver` as the last constructor argument:

```java
    return new OpenAiLlms.OpenAiIntentLlm(
        org.springframework.ai.chat.client.ChatClient.create(chatModel.getObject()),
        keywordParser,
        objectMapper,
        chatModelName(provider, openaiModel, geminiModel),
        provider,
        localityResolver);
```

(d) `backend/src/main/resources/application.yml` — both `temperature: 0.2` lines (OpenAI line 34, Gemini line 44) become `temperature: 0`.

- [ ] **Step 5: Run the new tests and the whole pure suite**

Run: `./mvnw test -Dtest='OpenAiLlmsPromptTest,IntentLocalitiesTest,OpenAiIntentLlmFinishTest,IntentLlmModeTest,MockIntentLlmTest,KeywordIntentParserTest,NewQueryDetectorTest' 2>&1 | tail -20`
Expected: `OpenAiLlmsPromptTest` 3, `IntentLocalitiesTest` 7, others unchanged; `Failures: 0`; `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/flatmaite/ai/OpenAiLlms.java src/main/java/com/flatmaite/ai/AiProviderConfig.java src/main/resources/application.yml src/main/java/com/flatmaite/search/IntentLocalities.java src/main/java/com/flatmaite/search/SearchPipeline.java src/test/java/com/flatmaite/ai/OpenAiLlmsPromptTest.java src/test/java/com/flatmaite/search/IntentLocalitiesTest.java
git commit -m "$(cat <<'EOF'
Give the intent prompt the locality vocabulary and worked examples; refine merges only

The prior intent moves to the user role, temperature drops to 0, and names
no resolver layer can place are surfaced as unresolvedLocations instead of
silently losing their filter.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Full verification, frontend check, README

**Files:**
- Modify: `README.md` — environment-variable table gains `SEARCH_NEARBY_RADIUS_MINUTES`

- [ ] **Step 1: README**

In `README.md`, in the "Backend (all optional in dev …)" environment table, add a row after `AI_EXPLANATIONS_ENABLED`:

```markdown
| `SEARCH_NEARBY_RADIUS_MINUTES` | `25` | A named home locality also admits every locality within this many estimated minutes |
```

- [ ] **Step 2: Full backend build (Docker running)**

Run: `./mvnw verify 2>&1 | grep -E "Tests run:.*in com\.|Tests run: [0-9]+, Failures|BUILD|ERROR\]" | tail -40`
Expected: every class green — new: `TokensTest` 5, `NumberWordsTest` 8, `TrigramsTest` 4, `LocalityResolverTest` 10, `LocationMentionsTest` 6, `ListingQueryServiceWhereTest` 2, `KeywordIntentParserTest` 12, `IntentLlmModeTest` 3, `OpenAiLlmsPromptTest` 3, `IntentLocalitiesTest` 7, `LocationWideningIntegrationTest` 4; changed: `MatchScorerTest` 11, `MockIntentLlmTest` 5, `NewQueryDetectorTest` 19, `SearchPipelineIntegrationTest` 5; unchanged WS1/WS0 suites — `BUILD SUCCESS`.

- [ ] **Step 3: Frontend type check**

Run (from `frontend/`): `npx tsc --noEmit 2>&1 | tail -5`
Expected: no output.

- [ ] **Step 4: Commit and confirm a clean tree**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
Document SEARCH_NEARBY_RADIUS_MINUTES

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git status --short
git log --oneline main..HEAD
```
Expected: clean tree; the spec commit plus eight task commits on `query-understanding`.
