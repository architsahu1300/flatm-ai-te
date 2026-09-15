package com.flatmaite.search;

import com.flatmaite.common.domain.RoomType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades how directly the user's own words support each filled slot. The question is deliberately
 * not "did our parser produce this" but "can I point at the span that says it" — the LLM reports no
 * spans, so its values and the keyword parser's must be graded by one rule.
 *
 * <p>Three levels and nothing between them: {@link #STATED} when a span says it outright,
 * {@link #WEAK} when a span supports it through a product convention (bare "room" meaning a private
 * room), {@link #INFERRED} when nothing in the query does — a shape guess, a default, or the
 * model's own reading. Only STATED and WEAK are meant to survive as hard filters once a confidence
 * gate is wired in front of this grading.
 */
public final class IntentGrounding {

  public static final double STATED = 1.0;
  public static final double WEAK = 0.75;
  public static final double INFERRED = 0.5;

  private static final Pattern AMOUNT_K = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k\\b");
  private static final Pattern AMOUNT_LAKH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:lakhs?|lac|l)\\b");
  private static final Pattern AMOUNT_PLAIN = Pattern.compile("\\b(\\d[\\d,]{3,8})\\b");
  private static final Pattern MINUTES = Pattern.compile("\\b\\d{1,3}\\s*(?:min|mins|minute|minutes)\\b");
  private static final Pattern BHK_WORD = Pattern.compile("\\b\\d\\s*bhk\\b|\\b1\\s*rk\\b|\\bstudio\\b");
  private static final Pattern DATE_WORD =
      Pattern.compile(
          "\\b(\\d{1,2}[/-]\\d{1,2}|\\d{4}-\\d{2}|today|tomorrow|asap|immediately|next month|"
              + "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\b");
  private static final Pattern ROOM_NOUN = Pattern.compile("\\b(room|rooms|pg|paying guest)\\b");

  private static final Map<String, String[]> FURNISHING_CUES =
      Map.of(
          "FULLY_FURNISHED", new String[] {"fully furnished", "full furnished", "furnished"},
          "SEMI_FURNISHED", new String[] {"semi furnished", "semi-furnished", "semifurnished"},
          "UNFURNISHED", new String[] {"unfurnished", "un furnished", "bare shell"});
  private static final Map<String, String[]> GENDER_CUES =
      Map.of(
          "FEMALE_ONLY", new String[] {"female", "girls", "girl", "ladies", "women", "ladki"},
          "MALE_ONLY", new String[] {"male", "boys", "boy", "men", "gents", "ladka"},
          "ANY", new String[] {"any gender", "anyone"});
  private static final Map<String, String[]> LIFESTYLE_CUES =
      Map.of(
          "smoking", new String[] {"smok", "cigarette"},
          "diet", new String[] {"veg", "non-veg", "nonveg", "eggetarian", "jain"},
          "pets", new String[] {"pet", "dog", "cat"},
          "quiet", new String[] {"quiet", "peaceful", "no parties", "silent"},
          "drinking", new String[] {"drink", "alcohol", "teetotal"},
          "sleepSchedule", new String[] {"early", "night owl", "late night"},
          "cleanliness", new String[] {"tidy", "clean", "neat"},
          "wfh", new String[] {"wfh", "work from home", "remote"},
          "partiesOk", new String[] {"part", "guests"});
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
              .allMatch(t -> q.contains(t.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
      out.put("listingTypes", all ? STATED : INFERRED);
    }
    if (intent.furnished() != null) {
      out.put("furnished", containsAny(q, FURNISHING_CUES.get(intent.furnished().name())) ? STATED : INFERRED);
    }
    if (intent.bhk() != null && (intent.bhk().min() != null || intent.bhk().max() != null)) {
      out.put("bhk", BHK_WORD.matcher(q).find() ? STATED : INFERRED);
    }
    if (intent.moveInDate() != null) {
      out.put("moveInDate", DATE_WORD.matcher(q).find() ? STATED : INFERRED);
    }
    if (intent.genderPreference() != null) {
      out.put(
          "genderPreference",
          containsAny(q, GENDER_CUES.get(intent.genderPreference().name())) ? STATED : INFERRED);
    }
    if (intent.couplesOk() != null) {
      out.put("couplesOk", containsAny(q, COUPLE_CUES) ? STATED : INFERRED);
    }
    if (intent.amenities() != null && !intent.amenities().isEmpty()) {
      boolean all = intent.amenities().stream().allMatch(a -> q.contains(a.toLowerCase(Locale.ROOT)));
      out.put("amenities", all ? STATED : INFERRED);
    }
    if (intent.lifestyle() != null) {
      out.put("lifestyle", lifestyleGrade(intent.lifestyle(), q));
    }
    if (intent.commuteTo() != null) {
      out.put("commuteTo", commuteAnchorGrade(intent.commuteTo(), matches));
      out.put("commuteTo.maxMinutes", MINUTES.matcher(q).find() ? STATED : INFERRED);
    }
    if (intent.verifiedOnly() != null) {
      out.put("verifiedOnly", containsAny(q, VERIFIED_CUES) ? STATED : INFERRED);
    }
    return out;
  }

  /** The best resolver confidence among the matches that could have produced these refs. */
  private static double placeGrade(List<SearchIntent.LocationRef> refs, List<LocalityResolver.Match> matches) {
    double best = INFERRED;
    for (SearchIntent.LocationRef ref : refs) {
      for (LocalityResolver.Match m : matches) {
        if (mentions(m, ref)) {
          best = Math.max(best, m.confidence());
        }
      }
    }
    return best;
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

  private static double lifestyleGrade(SearchIntent.Lifestyle lifestyle, String q) {
    int stated = 0;
    int total = 0;
    for (Map.Entry<String, String[]> e : LIFESTYLE_CUES.entrySet()) {
      if (facetOf(lifestyle, e.getKey()) == null) {
        continue;
      }
      total++;
      if (containsAny(q, e.getValue())) {
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
   * so a run embedded in a longer sentence still parses as a whole instead of word-by-word.
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
      value.ifPresent(out::add);
    }
    return Set.copyOf(out);
  }

  private static boolean containsAny(String q, String[] cues) {
    if (cues == null) {
      return false;
    }
    for (String cue : cues) {
      if (q.contains(cue)) {
        return true;
      }
    }
    return false;
  }
}
