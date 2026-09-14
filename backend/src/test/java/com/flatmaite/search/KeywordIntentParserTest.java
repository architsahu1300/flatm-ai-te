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
