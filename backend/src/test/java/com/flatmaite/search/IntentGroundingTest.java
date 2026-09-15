package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SearchTarget;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.SearchIntent.BhkRange;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.seed.SeedLocalities;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Grounding is the difference between what the user said and what the reader guessed. It must be
 * computable from the intent and the query alone — the LLM reports no spans, and its values have to
 * be graded by exactly the rule that grades the keyword parser's.
 */
class IntentGroundingTest {

  private static LocalityResolver resolver;

  @BeforeAll
  static void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(SeedLocalities.entities());
    resolver = new LocalityResolver(repo);
    resolver.reload();
  }

  private static UUID id(String name) {
    return SeedLocalities.id(name);
  }

  private static Map<String, Double> score(SearchIntent intent, String query) {
    return IntentGrounding.score(intent.toBuilder().originalQuery(query).build(), query, resolver);
  }

  @Test
  void statedBudget_isFullyGrounded_inEveryNotation() {
    assertThat(score(SearchIntent.builder().budgetMax(25000).build(), "room under 25k"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().budgetMax(25000).build(), "room under 25,000"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().budgetMin(30000).build(), "flat more than 30000"))
        .containsEntry("budgetMin", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().budgetMax(150000).build(), "one and a half lakh flat"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
  }

  @Test
  void aBudgetNoWordSupports_isInferred() {
    assertThat(score(SearchIntent.builder().budgetMax(25000).build(), "private room in powai"))
        .containsEntry("budgetMax", IntentGrounding.INFERRED);
  }

  @Test
  void roomType_hasThreeLevels() {
    // stated outright
    assertThat(score(SearchIntent.builder().roomType(RoomType.PRIVATE).build(), "private room in goregaon"))
        .containsEntry("roomType", IntentGrounding.STATED);
    // a bare occupancy noun supports it, but the mapping is our convention
    assertThat(score(SearchIntent.builder().roomType(RoomType.PRIVATE).build(), "room near bkc under 25k"))
        .containsEntry("roomType", IntentGrounding.WEAK);
    // pure shape inference — the reported bug
    assertThat(score(SearchIntent.builder().roomType(RoomType.ENTIRE).build(), "2bhk in powai under 40k"))
        .containsEntry("roomType", IntentGrounding.INFERRED);
  }

  @Test
  void localities_carryTheResolversOwnConfidence() {
    SearchIntent exact =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();
    assertThat(score(exact, "flats in powai")).containsEntry("locations", 1.0);

    SearchIntent fuzzy =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();
    assertThat(score(fuzzy, "flats in powaii").get("locations"))
        .isBetween(LocalityResolver.FUZZY_THRESHOLD, LocalityResolver.CONFIDENT);
  }

  @Test
  void aPlaceTheModelSuppliedButTheQueryNeverNames_isInferred() {
    SearchIntent invented =
        SearchIntent.builder().locations(List.of(new LocationRef("Powai", id("Powai")))).build();
    assertThat(score(invented, "somewhere with a gym")).containsEntry("locations", IntentGrounding.INFERRED);
  }

  @Test
  void commuteRadius_isGradedApartFromItsAnchor() {
    SearchIntent stated =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", id("BKC"), 20)).build();
    Map<String, Double> s = score(stated, "room within 20 min of bkc");
    assertThat(s).containsEntry("commuteTo", 1.0);
    assertThat(s).containsEntry("commuteTo.maxMinutes", IntentGrounding.STATED);

    SearchIntent defaulted =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", id("BKC"), 30)).build();
    Map<String, Double> d = score(defaulted, "room near bkc");
    assertThat(d).containsEntry("commuteTo", 1.0);
    assertThat(d).containsEntry("commuteTo.maxMinutes", IntentGrounding.INFERRED);
  }

  @Test
  void bhkFurnishingGenderVerified_areStatedOnlyWhenTheWordsAppear() {
    Map<String, Double> stated =
        score(
            SearchIntent.builder()
                .bhk(new BhkRange(2, 2))
                .furnished(Furnishing.FULLY_FURNISHED)
                .genderPreference(GenderPreference.FEMALE_ONLY)
                .verifiedOnly(true)
                .build(),
            "fully furnished 2bhk for girls, only verified listings");
    assertThat(stated)
        .containsEntry("bhk", IntentGrounding.STATED)
        .containsEntry("furnished", IntentGrounding.STATED)
        .containsEntry("genderPreference", IntentGrounding.STATED)
        .containsEntry("verifiedOnly", IntentGrounding.STATED);

    Map<String, Double> guessed =
        score(
            SearchIntent.builder()
                .bhk(new BhkRange(2, 2))
                .furnished(Furnishing.FULLY_FURNISHED)
                .build(),
            "nice place in powai");
    assertThat(guessed)
        .containsEntry("bhk", IntentGrounding.INFERRED)
        .containsEntry("furnished", IntentGrounding.INFERRED);
  }

  @Test
  void lifestyle_isGradedAcrossItsFacets() {
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").build())
                    .build(),
                "no smokers in andheri"))
        .containsEntry("lifestyle", IntentGrounding.STATED);
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(
                        SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").diet("VEGETARIAN").build())
                    .build(),
                "no smokers in andheri"))
        .containsEntry("lifestyle", IntentGrounding.WEAK);
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().diet("VEGETARIAN").build())
                    .build(),
                "1bhk in matunga"))
        .containsEntry("lifestyle", IntentGrounding.INFERRED);
  }

  @Test
  void onlyNonNullSlotsAreGraded_andEveryKeyIsAKnownSlot() {
    Map<String, Double> s = score(SearchIntent.builder().budgetMax(25000).build(), "under 25k");
    assertThat(s).containsOnlyKeys("budgetMax");
    assertThat(SearchIntent.GATED_SLOTS).containsAll(s.keySet());
  }

  @Test
  void aNullOrBlankQuery_gradesEverythingInferred_ratherThanThrowing() {
    SearchIntent intent = SearchIntent.builder().roomType(RoomType.PRIVATE).budgetMax(25000).build();
    assertThat(IntentGrounding.score(intent, null, resolver))
        .containsEntry("roomType", IntentGrounding.INFERRED)
        .containsEntry("budgetMax", IntentGrounding.INFERRED);
    assertThat(IntentGrounding.score(intent, "   ", resolver)).isNotEmpty();
  }

  @Test
  void searchTargetAndFreeText_areNeverGraded() {
    Map<String, Double> s =
        score(
            SearchIntent.builder().searchTarget(SearchTarget.FLATMATES).freeText("sea view").build(),
            "flatmate with a sea view");
    assertThat(s).doesNotContainKeys("searchTarget", "freeText", "originalQuery");
  }

  @Test
  void amountsAreMatchedWithinTolerance_notByStringEquality() {
    // "40k" renders 40000 exactly; a model that returns 40000 for "40 thousand" is still grounded
    assertThat(score(SearchIntent.builder().budgetMax(40000).build(), "budget 40 thousand"))
        .containsEntry("budgetMax", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().maxDeposit(200000).build(), "2 lakh deposit, 30k rent"))
        .containsEntry("maxDeposit", IntentGrounding.STATED);
  }

  @Test
  void aCueInsideALongerWordDoesNotGroundAnything() {
    // "apartment" contains "part" (parties) and "men" (MALE_ONLY); "carpet" contains "pet"
    SearchIntent intent =
        SearchIntent.builder()
            .genderPreference(GenderPreference.MALE_ONLY)
            .lifestyle(SearchIntent.Lifestyle.builder().partiesOk(true).pets("PET_FRIENDLY").build())
            .build();

    Map<String, Double> graded = score(intent, "2bhk apartment in powai with a carpet area of 600 sqft");

    assertThat(graded).containsEntry("genderPreference", IntentGrounding.INFERRED);
    assertThat(graded).containsEntry("lifestyle", IntentGrounding.INFERRED);
  }

  @Test
  void aRealCueStillGroundsWhenItStartsAWord() {
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").build())
                    .build(),
                "no smokers please"))
        .containsEntry("lifestyle", IntentGrounding.STATED);
    assertThat(
            score(
                SearchIntent.builder().genderPreference(GenderPreference.FEMALE_ONLY).build(),
                "women only pg in thane"))
        .containsEntry("genderPreference", IntentGrounding.STATED);
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().diet("VEGETARIAN").build())
                    .build(),
                "vegetarian household"))
        .containsEntry("lifestyle", IntentGrounding.STATED);
  }

  @Test
  void anAmenitySlugMustAppearAsAWholeWord() {
    SearchIntent intent = SearchIntent.builder().amenities(List.of("ac")).build();
    assertThat(score(intent, "accommodation in powai with good accessibility"))
        .containsEntry("amenities", IntentGrounding.INFERRED);
    assertThat(score(intent, "2bhk in powai with ac")).containsEntry("amenities", IntentGrounding.STATED);
  }

  @Test
  void aSemiFurnishedQueryDoesNotGroundAFullyFurnishedClaim() {
    assertThat(score(SearchIntent.builder().furnished(Furnishing.FULLY_FURNISHED).build(), "semi furnished room in chembur"))
        .containsEntry("furnished", IntentGrounding.INFERRED);
    assertThat(score(SearchIntent.builder().furnished(Furnishing.SEMI_FURNISHED).build(), "semi furnished room in chembur"))
        .containsEntry("furnished", IntentGrounding.STATED);
    assertThat(score(SearchIntent.builder().furnished(Furnishing.FULLY_FURNISHED).build(), "fully furnished 1bhk"))
        .containsEntry("furnished", IntentGrounding.STATED);
  }

  @Test
  void oneInventedLocalityDragsTheWholeListDown() {
    SearchIntent both =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", id("Powai")), new LocationRef("Bandra", id("Bandra"))))
            .build();
    assertThat(score(both, "flats in powai")).containsEntry("locations", IntentGrounding.INFERRED);
    assertThat(score(both, "flats in powai or bandra")).containsEntry("locations", 1.0);
  }

  @Test
  void anAmbiguousAliasStillGroundsBothOfItsLocalities() {
    SearchIntent andheri =
        SearchIntent.builder()
            .locations(
                List.of(
                    new LocationRef("Andheri East", id("Andheri East")),
                    new LocationRef("Andheri West", id("Andheri West"))))
            .build();
    assertThat(score(andheri, "room in andheri")).containsEntry("locations", 1.0);
  }

  @Test
  void aBhkClaimMustMatchTheBhkInTheQuery() {
    SearchIntent three = SearchIntent.builder().bhk(new BhkRange(3, 3)).build();
    assertThat(score(three, "1bhk in powai")).containsEntry("bhk", IntentGrounding.INFERRED);
    assertThat(score(three, "3bhk in powai")).containsEntry("bhk", IntentGrounding.STATED);
  }

  @Test
  void aCommuteRadiusClaimMustMatchTheMinutesInTheQuery() {
    SearchIntent wide =
        SearchIntent.builder().commuteTo(new CommuteTo("BKC", id("BKC"), 45)).build();
    assertThat(score(wide, "room within 20 min of bkc"))
        .containsEntry("commuteTo.maxMinutes", IntentGrounding.INFERRED);
    assertThat(score(SearchIntent.builder().commuteTo(new CommuteTo("BKC", id("BKC"), 20)).build(), "room within 20 min of bkc"))
        .containsEntry("commuteTo.maxMinutes", IntentGrounding.STATED);
  }

  @Test
  void aCueAtTheStartOfALongerWordDoesNotGroundAnything() {
    assertThat(
            score(
                SearchIntent.builder()
                    .lifestyle(SearchIntent.Lifestyle.builder().diet("VEGETARIAN").pets("PET_FRIENDLY").build())
                    .build(),
                "flat near the vegetable market in a quiet category of building"))
        .containsEntry("lifestyle", IntentGrounding.INFERRED);
    assertThat(score(SearchIntent.builder().genderPreference(GenderPreference.MALE_ONLY).build(), "please mention the rent"))
        .containsEntry("genderPreference", IntentGrounding.INFERRED);
  }

  @Test
  void aMoveInDateIsWeakBecauseWeCannotConfirmItIsThatDate() {
    assertThat(score(SearchIntent.builder().moveInDate("2026-10-01").build(), "moving in next month"))
        .containsEntry("moveInDate", IntentGrounding.WEAK);
    assertThat(score(SearchIntent.builder().moveInDate("2026-10-01").build(), "2bhk in powai"))
        .containsEntry("moveInDate", IntentGrounding.INFERRED);
  }

  @Test
  void aSquareFootageDoesNotGroundABudget() {
    assertThat(score(SearchIntent.builder().budgetMax(600).build(), "600 sqft flat in powai"))
        .containsEntry("budgetMax", IntentGrounding.INFERRED);
  }
}
