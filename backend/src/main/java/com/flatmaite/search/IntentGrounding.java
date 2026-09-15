package com.flatmaite.search;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.RoomType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades how directly the user's own words support each filled slot. The question is deliberately
 * not "did our parser produce this" but "can I point at the span that says it" — the LLM reports no
 * spans, so its values and the keyword parser's must be graded by one rule.
 *
 * <p>Three levels and nothing between them: {@link #STATED} when a span says it outright,
 * {@link #WEAK} when a span supports it through a product convention (bare "room" meaning a private
 * room) or through a signal we cannot fully verify (a date mention we cannot confirm is *this*
 * date), {@link #INFERRED} when nothing in the query does — a shape guess, a default, or the
 * model's own reading. Only STATED and WEAK are meant to survive as hard filters once a confidence
 * gate is wired in front of this grading.
 *
 * <p><b>The grading may be too low, never too high.</b> A false-high grade keeps a value the reader
 * invented as a hard SQL filter, deleting listings the user never asked to exclude — every rule
 * below is written to fail toward INFERRED rather than toward STATED whenever it cannot be sure.
 */
public final class IntentGrounding {

  public static final double STATED = 1.0;
  public static final double WEAK = 0.75;
  public static final double INFERRED = 0.5;

  private static final Pattern AMOUNT_K = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k\\b");
  private static final Pattern AMOUNT_LAKH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:lakhs?|lac|l)\\b");
  private static final Pattern AMOUNT_PLAIN = Pattern.compile("\\b(\\d[\\d,]{3,8})\\b");
  private static final Pattern BHK_NUMBER = Pattern.compile("\\b(\\d)\\s*bhk\\b");
  private static final Pattern STUDIO_WORD = Pattern.compile("\\b1\\s*rk\\b|\\bstudio\\b");
  private static final Pattern MINUTES_VALUE =
      Pattern.compile("\\b(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b");
  private static final Pattern DATE_WORD =
      Pattern.compile(
          "\\b(\\d{1,2}[/-]\\d{1,2}|\\d{4}-\\d{2}|today|tomorrow|asap|immediately|next month|"
              + "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\b");
  private static final Pattern ROOM_NOUN = Pattern.compile("\\b(room|rooms|pg|paying guest)\\b");

  private static final Map<String, String[]> GENDER_CUES =
      Map.of(
          "FEMALE_ONLY",
          new String[] {"female", "females", "girl", "girls", "ladies", "lady", "women", "woman", "ladki"},
          "MALE_ONLY",
          new String[] {"male", "males", "boy", "boys", "men", "man", "gents", "bachelors", "ladka"},
          "ANY",
          new String[] {"any gender", "anyone", "no preference"});
  private static final Map<String, String[]> LIFESTYLE_CUES =
      Map.of(
          "smoking",
          new String[] {
            "smoke", "smoker", "smokers", "smoking", "cigarette", "cigarettes", "non-smoker", "nonsmoker"
          },
          "diet",
          new String[] {"veg", "vegetarian", "veggie", "non-veg", "nonveg", "eggetarian", "jain"},
          "pets",
          new String[] {"pet", "pets", "dog", "dogs", "cat", "cats", "pet-friendly"},
          "quiet",
          new String[] {"quiet", "peaceful", "silent", "no parties"},
          "drinking",
          new String[] {"drink", "drinks", "drinking", "alcohol", "teetotal"},
          "sleepSchedule",
          new String[] {"early riser", "early bird", "night owl", "late night"},
          "cleanliness",
          new String[] {"tidy", "clean", "neat", "hygienic"},
          "wfh",
          new String[] {"wfh", "work from home", "remote"},
          "partiesOk",
          new String[] {"party", "parties", "guests"});
  private static final String[] COUPLE_CUES = {"couple", "couples", "married"};
  private static final String[] VERIFIED_CUES = {"verified", "verification"};

  private IntentGrounding() {}

  /** Grades every non-null gated slot. Never throws: an unusable query grades everything INFERRED. */
  public static Map<String, Double> score(SearchIntent intent, String query, LocalityResolver resolver) {
    String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
    Map<String, Double> out = new LinkedHashMap<>();

    Set<Integer> amounts = amountsIn(q);
    List<LocalityResolver.Match> matches = q.isBlank() ? List.of() : resolver.scan(q);

    if (intent.locations() != null && !intent.locations().isEmpty()) {
      out.put("locations", placeGrade(intent.locations(), matches));
    }
    if (intent.excludeLocations() != null && !intent.excludeLocations().isEmpty()) {
      out.put("excludeLocations", placeGrade(intent.excludeLocations(), matches));
    }
    if (intent.budgetMin() != null) {
      out.put("budgetMin", amounts.contains(intent.budgetMin()) ? STATED : INFERRED);
    }
    if (intent.budgetMax() != null) {
      out.put("budgetMax", amounts.contains(intent.budgetMax()) ? STATED : INFERRED);
    }
    if (intent.maxDeposit() != null) {
      out.put("maxDeposit", amounts.contains(intent.maxDeposit()) ? STATED : INFERRED);
    }
    if (intent.roomType() != null) {
      out.put("roomType", roomTypeGrade(intent.roomType(), q));
    }
    if (intent.listingTypes() != null && !intent.listingTypes().isEmpty()) {
      boolean all =
          intent.listingTypes().stream()
              .allMatch(t -> containsWord(q, t.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
      out.put("listingTypes", all ? STATED : INFERRED);
    }
    if (intent.furnished() != null) {
      out.put("furnished", intent.furnished() == statedFurnishing(q) ? STATED : INFERRED);
    }
    if (intent.bhk() != null) {
      out.put("bhk", bhkGrade(intent.bhk(), q));
    }
    if (intent.moveInDate() != null) {
      // A free-text date mention only proves *a* date was discussed; confirming it is the same
      // date as the normalised moveInDate needs real date parsing, which is out of scope. WEAK
      // still enforces the filter but yields before a slot the user stated outright.
      out.put("moveInDate", DATE_WORD.matcher(q).find() ? WEAK : INFERRED);
    }
    if (intent.genderPreference() != null) {
      out.put(
          "genderPreference",
          containsWord(q, GENDER_CUES.get(intent.genderPreference().name())) ? STATED : INFERRED);
    }
    if (intent.couplesOk() != null) {
      out.put("couplesOk", containsWord(q, COUPLE_CUES) ? STATED : INFERRED);
    }
    if (intent.amenities() != null && !intent.amenities().isEmpty()) {
      boolean all = intent.amenities().stream().allMatch(a -> containsWord(q, normalizeSlug(a)));
      out.put("amenities", all ? STATED : INFERRED);
    }
    if (intent.lifestyle() != null) {
      out.put("lifestyle", lifestyleGrade(intent.lifestyle(), q));
    }
    if (intent.commuteTo() != null) {
      out.put("commuteTo", commuteAnchorGrade(intent.commuteTo(), matches));
      out.put("commuteTo.maxMinutes", minutesGrade(intent.commuteTo().maxMinutes(), q));
    }
    if (intent.verifiedOnly() != null) {
      out.put("verifiedOnly", containsWord(q, VERIFIED_CUES) ? STATED : INFERRED);
    }
    return out;
  }

  /**
   * The filter applies to the whole list at once, so it can only be as trustworthy as its weakest
   * member: one invented locality alongside a real one must not let the real one's confidence carry
   * the pair. An alias that expands to several refs from a single match (e.g. "andheri") still
   * grades every one of those refs against that match, so it is unaffected.
   */
  private static double placeGrade(List<SearchIntent.LocationRef> refs, List<LocalityResolver.Match> matches) {
    double worst = STATED;
    for (SearchIntent.LocationRef ref : refs) {
      double best = INFERRED;
      for (LocalityResolver.Match m : matches) {
        if (mentions(m, ref)) {
          best = Math.max(best, m.confidence());
        }
      }
      worst = Math.min(worst, best);
    }
    return refs.isEmpty() ? INFERRED : worst;
  }

  private static double commuteAnchorGrade(SearchIntent.CommuteTo commute, List<LocalityResolver.Match> matches) {
    double best = INFERRED;
    for (LocalityResolver.Match m : matches) {
      boolean sameId = commute.localityId() != null && m.localityIds().contains(commute.localityId());
      boolean sameName =
          commute.place() != null && m.canonicalName().equalsIgnoreCase(commute.place().trim());
      if (sameId || sameName) {
        best = Math.max(best, m.confidence());
      }
    }
    return best;
  }

  private static boolean mentions(LocalityResolver.Match m, SearchIntent.LocationRef ref) {
    UUID id = ref.localityId();
    if (id != null && m.localityIds().contains(id)) {
      return true;
    }
    return ref.name() != null && m.canonicalName().equalsIgnoreCase(ref.name().trim());
  }

  private static double roomTypeGrade(RoomType type, String q) {
    if (type == RentalVocabulary.explicitRoomType(q)) {
      return STATED;
    }
    // a bare occupancy noun supports PRIVATE/SHARED; "2bhk" alone supports nothing
    if ((type == RoomType.PRIVATE || type == RoomType.SHARED) && ROOM_NOUN.matcher(q).find()) {
      return WEAK;
    }
    return INFERRED;
  }

  /** The furnishing the query states, most specific phrase first, or null. */
  private static Furnishing statedFurnishing(String q) {
    if (containsWord(q, "semi furnished", "semi-furnished", "semifurnished")) {
      return Furnishing.SEMI_FURNISHED;
    }
    if (containsWord(q, "unfurnished", "un furnished", "bare shell", "empty flat")) {
      return Furnishing.UNFURNISHED;
    }
    if (containsWord(q, "fully furnished", "full furnished", "furnished")) {
      return Furnishing.FULLY_FURNISHED;
    }
    return null;
  }

  /**
   * STATED when some "N bhk" in the query falls inside the claimed range. When the claim carries no
   * numeric bound at all (both ends null — a studio claim has no bhk count to match), STATED only
   * when the query itself names a studio/1RK; otherwise INFERRED.
   */
  private static double bhkGrade(SearchIntent.BhkRange bhk, String q) {
    if (bhk.min() != null || bhk.max() != null) {
      Matcher m = BHK_NUMBER.matcher(q);
      while (m.find()) {
        int n = Integer.parseInt(m.group(1));
        boolean withinMin = bhk.min() == null || n >= bhk.min();
        boolean withinMax = bhk.max() == null || n <= bhk.max();
        if (withinMin && withinMax) {
          return STATED;
        }
      }
      return INFERRED;
    }
    return STUDIO_WORD.matcher(q).find() ? STATED : INFERRED;
  }

  /** STATED when some "N min(s)" in the query equals the claimed radius exactly; else INFERRED. */
  private static double minutesGrade(Integer claimedMinutes, String q) {
    if (claimedMinutes == null) {
      return INFERRED;
    }
    Matcher m = MINUTES_VALUE.matcher(q);
    while (m.find()) {
      if (Integer.parseInt(m.group(1)) == claimedMinutes) {
        return STATED;
      }
    }
    return INFERRED;
  }

  private static double lifestyleGrade(SearchIntent.Lifestyle lifestyle, String q) {
    int stated = 0;
    int total = 0;
    for (Map.Entry<String, String[]> e : LIFESTYLE_CUES.entrySet()) {
      if (facetOf(lifestyle, e.getKey()) == null) {
        continue;
      }
      total++;
      if (containsWord(q, e.getValue())) {
        stated++;
      }
    }
    if (total == 0 || stated == 0) {
      return INFERRED;
    }
    return stated == total ? STATED : WEAK;
  }

  private static Object facetOf(SearchIntent.Lifestyle l, String facet) {
    return switch (facet) {
      case "smoking" -> l.smoking();
      case "diet" -> l.diet();
      case "pets" -> l.pets();
      case "quiet" -> l.quiet();
      case "drinking" -> l.drinking();
      case "sleepSchedule" -> l.sleepSchedule();
      case "cleanliness" -> l.cleanliness();
      case "wfh" -> l.wfh();
      case "partiesOk" -> l.partiesOk();
      default -> null;
    };
  }

  /**
   * Every rupee amount the query states, in every notation the parser accepts: digit shorthand
   * (25k, 1.5 lakh), plain runs of digits, and spelled-out phrases ("one and a half lakh") located
   * via {@link NumberWords#NUMBER_RUN} — the same public pattern {@code KeywordIntentParser} uses,
   * so a run embedded in a longer sentence still parses as a whole instead of word-by-word. Runs
   * below 1,000 are dropped, matching {@code KeywordIntentParser}'s own floor, so a square-footage
   * figure like "600 sqft" can never ground a budget.
   */
  private static Set<Integer> amountsIn(String q) {
    List<Integer> out = new ArrayList<>();
    Matcher k = AMOUNT_K.matcher(q);
    while (k.find()) {
      out.add((int) Math.round(Double.parseDouble(k.group(1)) * 1000));
    }
    Matcher lakh = AMOUNT_LAKH.matcher(q);
    while (lakh.find()) {
      out.add((int) Math.round(Double.parseDouble(lakh.group(1)) * 100_000));
    }
    Matcher plain = AMOUNT_PLAIN.matcher(q);
    while (plain.find()) {
      out.add(Integer.parseInt(plain.group(1).replace(",", "")));
    }
    Matcher words = NumberWords.NUMBER_RUN.matcher(q);
    while (words.find()) {
      OptionalInt value = NumberWords.parse(words.group());
      if (value.isPresent() && value.getAsInt() >= 1_000) {
        out.add(value.getAsInt());
      }
    }
    return Set.copyOf(out);
  }

  /** "parking_2w" -> "parking 2w", so a slug can match the spaced-out way it appears in prose. */
  private static String normalizeSlug(String slug) {
    return slug.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
  }

  private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

  /** A value (an amenity slug, a listing type, a cue phrase) must appear as a whole word — "ac" is
   * not "accommodation", "men" is not "apartment", "part" is not "apartment" either. */
  private static boolean containsWord(String q, String... words) {
    if (words == null) {
      return false;
    }
    for (String w : words) {
      if (w == null || w.isBlank()) {
        continue;
      }
      if (PATTERNS.computeIfAbsent(w, k -> Pattern.compile("\\b" + Pattern.quote(k) + "\\b")).matcher(q).find()) {
        return true;
      }
    }
    return false;
  }
}
