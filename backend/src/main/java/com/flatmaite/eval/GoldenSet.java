package com.flatmaite.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flatmaite.search.NewQueryDetector;
import com.flatmaite.search.SearchIntent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The golden set, validated on load: unique ids, priors that name an earlier case, only known
 * {@code expect} keys (a typo would otherwise read as "expected null" and fail confusingly), and
 * no must-pass case tagged known-gap.
 */
public final class GoldenSet {

  public static final String RESOURCE = "eval/intent-golden.json";

  private static final Set<String> EXPECT_KEYS =
      Set.of("searchTarget", "locations", "excludeLocations", "unresolvedLocations", "commuteTo",
          "budgetMin", "budgetMax", "maxDeposit", "roomType", "listingTypes", "bhk", "furnished",
          "genderPreference", "couplesOk", "verifiedOnly", "amenities", "lifestyle");
  private static final Set<String> COMMUTE_KEYS = Set.of("place", "maxMinutes");
  private static final Set<String> BHK_KEYS = Set.of("min", "max");
  private static final Set<String> LIFESTYLE_KEYS = Set.of("smoking", "pets", "diet", "quiet");
  private static final Set<String> NAME_LIST_KEYS = Set.of("locations", "excludeLocations");
  private static final Set<String> CASE_KEYS =
      Set.of("id", "tags", "query", "prior", "mustPass", "expectVerdict", "scoreIntent", "expect");
  private static final Set<String> PRIOR_KEYS = Set.of("case");

  private final int version;
  private final List<GoldenCase> all;
  private final List<GoldenCase> selected;
  private final Map<String, GoldenCase> byId;

  private GoldenSet(int version, List<GoldenCase> all, List<GoldenCase> selected) {
    this.version = version;
    this.all = List.copyOf(all);
    this.selected = List.copyOf(selected);
    Map<String, GoldenCase> index = new HashMap<>();
    for (GoldenCase c : all) {
      index.put(c.id(), c);
    }
    this.byId = Map.copyOf(index);
  }

  public static GoldenSet load() {
    try (InputStream in = GoldenSet.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("golden set not on the classpath: " + RESOURCE);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
  }

  public static GoldenSet parse(String json) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      JsonNode root = mapper.readTree(json);
      int version = root.path("version").asInt(-1);
      if (version != 1) {
        throw new IllegalStateException("golden set version must be 1, was " + version);
      }
      List<GoldenCase> cases = new ArrayList<>();
      Set<String> seen = new java.util.HashSet<>();
      for (JsonNode node : root.path("cases")) {
        GoldenCase c = readCase(mapper, node, seen);
        seen.add(c.id());
        cases.add(c);
      }
      return new GoldenSet(version, cases, cases);
    } catch (IOException e) {
      throw new IllegalStateException("golden set is not valid JSON", e);
    }
  }

  private static GoldenCase readCase(ObjectMapper mapper, JsonNode node, Set<String> earlier) throws IOException {
    String id = node.path("id").asText(null);
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("golden case without an id: " + node);
    }
    if (earlier.contains(id)) {
      throw new IllegalStateException("duplicate golden case id: " + id);
    }
    validateKeys(id, node, CASE_KEYS, "case");
    List<String> tags = new ArrayList<>();
    node.path("tags").forEach(t -> tags.add(t.asText()));
    String query = node.path("query").asText(null);
    if (query == null) {
      throw new IllegalStateException("golden case " + id + " has no query");
    }
    String priorCase = null;
    JsonNode prior = node.path("prior");
    if (!prior.isMissingNode() && !prior.isNull()) {
      validateKeys(id, prior, PRIOR_KEYS, "prior");
      priorCase = prior.path("case").asText(null);
      if (priorCase == null || !earlier.contains(priorCase)) {
        throw new IllegalStateException("golden case " + id + ": prior must name an earlier case, was " + priorCase);
      }
    }
    boolean mustPass = node.path("mustPass").asBoolean(false);
    boolean scoreIntent = node.path("scoreIntent").asBoolean(true);
    NewQueryDetector.Verdict verdict = null;
    JsonNode v = node.path("expectVerdict");
    if (!v.isMissingNode() && !v.isNull()) {
      try {
        verdict = NewQueryDetector.Verdict.valueOf(v.asText());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            "golden case " + id + ": expectVerdict must be NEW, REFINE or AMBIGUOUS, was '" + v.asText() + "'");
      }
    }
    if (tags.contains(GoldenCase.KNOWN_GAP) && mustPass) {
      throw new IllegalStateException("golden case " + id + " is known-gap and must-pass at once");
    }
    JsonNode expect = node.path("expect");
    if (!expect.isObject()) {
      throw new IllegalStateException("golden case " + id + " needs an expect object");
    }
    validateKeys(id, expect, EXPECT_KEYS, "expect");
    validateKeys(id, expect.path("commuteTo"), COMMUTE_KEYS, "expect.commuteTo");
    validateKeys(id, expect.path("bhk"), BHK_KEYS, "expect.bhk");
    validateKeys(id, expect.path("lifestyle"), LIFESTYLE_KEYS, "expect.lifestyle");
    SearchIntent expected;
    try {
      expected = mapper.treeToValue(toIntentNode(mapper, (ObjectNode) expect), SearchIntent.class);
    } catch (IllegalArgumentException | IOException e) {
      throw new IllegalStateException("golden case " + id + ": expect does not deserialise — " + e.getMessage(), e);
    }
    return new GoldenCase(id, List.copyOf(tags), query, priorCase, mustPass, verdict, scoreIntent, expected);
  }

  /** Golden files write place names as strings; the intent wants LocationRef objects. */
  private static ObjectNode toIntentNode(ObjectMapper mapper, ObjectNode expect) {
    ObjectNode out = expect.deepCopy();
    for (String key : NAME_LIST_KEYS) {
      if (out.has(key) && out.get(key).isArray()) {
        ArrayNode refs = mapper.createArrayNode();
        for (JsonNode name : out.get(key)) {
          refs.add(mapper.createObjectNode().put("name", name.asText()));
        }
        out.set(key, refs);
      }
    }
    return out;
  }

  private static void validateKeys(String id, JsonNode obj, Set<String> allowed, String where) {
    if (!obj.isObject()) {
      return;
    }
    for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!allowed.contains(key)) {
        throw new IllegalStateException("golden case " + id + ": unknown key '" + key + "' in " + where);
      }
    }
  }

  public int version() {
    return version;
  }

  /** The cases to run (all of them, or the filtered selection). */
  public List<GoldenCase> cases() {
    return selected;
  }

  /** The expected intent of the case's prior, or null for a first turn. */
  public SearchIntent priorOf(GoldenCase c) {
    return c.priorCase() == null ? null : byId.get(c.priorCase()).expected();
  }

  /** Empty tags = every case; limit 0 = no limit. Priors stay resolvable through the full index. */
  public GoldenSet filter(Set<String> tags, int limit) {
    List<GoldenCase> out = new ArrayList<>();
    for (GoldenCase c : all) {
      if (!tags.isEmpty() && c.tags().stream().noneMatch(tags::contains)) {
        continue;
      }
      out.add(c);
      if (limit > 0 && out.size() >= limit) {
        break;
      }
    }
    return new GoldenSet(version, all, out);
  }
}
