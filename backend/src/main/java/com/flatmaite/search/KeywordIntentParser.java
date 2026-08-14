package com.flatmaite.search;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.search.SearchIntent.BhkRange;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.Lifestyle;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministic natural-language parser. Serves three roles: the keyless mock "LLM", the
 * degradation fallback when OpenAI misbehaves, and the base the real LLM's output is validated
 * against. Handles every example query in the product spec.
 */
@Component
@RequiredArgsConstructor
public class KeywordIntentParser {

  private static final Pattern BUDGET_K = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k\\b");
  private static final Pattern BUDGET_PLAIN =
      Pattern.compile("(?:under|below|max(?:imum)?|budget(?:\\s+(?:of|is))?|upto|up to|than)\\s*(?:rs\\.?|₹|inr)?\\s*(\\d{4,7})\\b");
  private static final Pattern BUDGET_ANY_RUPEE = Pattern.compile("(?:rs\\.?|₹|inr)\\s*(\\d{4,7})\\b");
  private static final Pattern LAKH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:lakh|lac|l)\\b");
  private static final Pattern BHK = Pattern.compile("(\\d)\\s*bhk");
  private static final Pattern COMMUTE_MIN =
      Pattern.compile("(?:within|in|under|less than)\\s*(\\d{1,3})\\s*min(?:ute)?s?\\s*(?:of|from|to)?\\s*(?:work|office)?");

  private final LocalityResolver localityResolver;

  public SearchIntent parse(String query) {
    String q = query.toLowerCase(Locale.ROOT);

    // --- target ---
    SearchTarget target = SearchTarget.PROPERTIES;
    boolean mentionsPeople =
        q.contains("flatmate")
            || q.contains("roommate")
            || q.contains("room mate")
            || q.contains("someone")
            || q.contains("compatible with me")
            || q.contains("person to share");
    boolean mentionsPlace =
        q.contains("room")
            || q.contains("flat ")
            || q.endsWith("flat")
            || q.contains("bhk")
            || q.contains("apartment")
            || q.contains("house")
            || q.contains("pg");
    boolean wantsFlatmateWithFlat = q.contains("flatmate") && (q.contains("my flat") || q.contains("my place"));
    if (mentionsPeople && !wantsFlatmateWithFlat) {
      // "find a flatmate who ..." → people; "flat with people who don't smoke" → properties
      boolean flatWithPeople = q.matches(".*\\b(flat|room|apartment|bhk)s?\\b.*\\bwith\\b.*\\b(people|flatmates)\\b.*");
      target = flatWithPeople ? SearchTarget.PROPERTIES : SearchTarget.FLATMATES;
    }

    // --- budget ---
    Integer budgetMax = null;
    Integer maxDeposit = null;
    boolean depositContext = q.contains("deposit");
    Matcher lakh = LAKH.matcher(q);
    if (lakh.find()) {
      int amount = (int) (Double.parseDouble(lakh.group(1)) * 100_000);
      if (depositContext) {
        maxDeposit = amount;
      } else {
        budgetMax = amount;
      }
    }
    Matcher k = BUDGET_K.matcher(q);
    if (k.find()) {
      int amount = (int) (Double.parseDouble(k.group(1)) * 1000);
      if (depositContext && maxDeposit == null && q.indexOf("deposit") < k.start()) {
        maxDeposit = amount;
      } else if (budgetMax == null) {
        budgetMax = amount;
      }
    }
    if (budgetMax == null) {
      Matcher plain = BUDGET_PLAIN.matcher(q);
      if (plain.find() && !depositContext) {
        budgetMax = Integer.parseInt(plain.group(1));
      } else {
        Matcher rupee = BUDGET_ANY_RUPEE.matcher(q);
        if (rupee.find()) {
          budgetMax = Integer.parseInt(rupee.group(1));
        }
      }
    }

    // --- locations & commute ---
    List<LocationRef> locations = new ArrayList<>();
    for (UUID id : localityResolver.scan(q)) {
      locations.add(new LocationRef(localityResolver.nameOf(id), id));
    }
    CommuteTo commuteTo = null;
    boolean nearContext =
        q.contains("near ") || q.contains("close to") || q.contains("within") || q.contains("min of") || q.contains("minutes of") || q.contains("from work");
    if (!locations.isEmpty() && nearContext) {
      Integer maxMinutes = null;
      Matcher cm = COMMUTE_MIN.matcher(q);
      if (cm.find()) {
        maxMinutes = Integer.parseInt(cm.group(1));
      }
      LocationRef anchor = locations.get(0);
      commuteTo = new CommuteTo(anchor.name(), anchor.localityId(), maxMinutes == null ? 45 : maxMinutes);
    }

    // --- room / property shape ---
    RoomType roomType = null;
    List<ListingType> listingTypes = null;
    BhkRange bhkRange = null;
    Matcher bhk = BHK.matcher(q);
    if (bhk.find()) {
      int n = Integer.parseInt(bhk.group(1));
      bhkRange = new BhkRange(n, n);
    }
    if (q.contains("shared room") || q.contains("sharing")) {
      roomType = RoomType.SHARED;
    } else if (q.contains("private room") || (q.contains("room") && !q.contains("bhk"))) {
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
    Boolean drinkingNo = q.contains("don't drink") || q.contains("doesn't drink") || q.contains("no drinking");

    // --- gender ---
    GenderPreference gender = null;
    if (q.contains("female flatmate") || q.contains("girl flatmate") || q.contains("female only")
        || q.contains("girls only") || q.contains("for female") || q.contains("women only")) {
      gender = GenderPreference.FEMALE_ONLY;
    } else if (q.contains("male flatmate") || q.contains("male only") || q.contains("boys only")) {
      gender = GenderPreference.MALE_ONLY;
    }

    // --- misc ---
    Boolean verifiedOnly = q.contains("verified") ? true : null;
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
}
