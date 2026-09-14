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
