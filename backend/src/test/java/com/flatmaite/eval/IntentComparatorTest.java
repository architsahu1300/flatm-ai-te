package com.flatmaite.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.eval.IntentComparator.SlotResult;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class IntentComparatorTest {

  private static final UUID POWAI = UUID.randomUUID();
  private static final UUID ANDHERI_E = UUID.randomUUID();
  private static final UUID ANDHERI_W = UUID.randomUUID();
  private static final Function<LocationRef, String> NAME_OF =
      ref -> Map.of(POWAI, "Powai", ANDHERI_E, "Andheri East", ANDHERI_W, "Andheri West").getOrDefault(ref.localityId(), ref.name());

  private static Map<String, SlotResult> bySlot(List<SlotResult> results) {
    return results.stream().collect(java.util.stream.Collectors.toMap(SlotResult::slot, r -> r));
  }

  @Test
  void everySlotIsReported_inOrder() {
    List<SlotResult> r = IntentComparator.compare(SearchIntent.builder().build(), SearchIntent.builder().build(), NAME_OF);
    assertThat(r).extracting(SlotResult::slot).containsExactlyElementsOf(IntentComparator.SLOTS);
    assertThat(r).allMatch(SlotResult::match);
  }

  @Test
  void scalarMismatch_carriesBothValues() {
    SearchIntent expected = SearchIntent.builder().budgetMax(25000).build();
    SearchIntent actual = SearchIntent.builder().budgetMin(25000).build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("budgetMax").match()).isFalse();
    assertThat(r.get("budgetMax").expected()).isEqualTo("25000");
    assertThat(r.get("budgetMax").actual()).isEqualTo("null");
    assertThat(r.get("budgetMin").match()).isFalse(); // omitted expectation means null
  }

  @Test
  void locations_compareByCanonicalName_orderInsensitive() {
    SearchIntent expected = SearchIntent.builder()
        .locations(List.of(new LocationRef("Andheri West", null), new LocationRef("Andheri East", null))).build();
    SearchIntent actual = SearchIntent.builder()
        .locations(List.of(new LocationRef("Andheri East", ANDHERI_E), new LocationRef("Andheri West", ANDHERI_W))).build();
    assertThat(bySlot(IntentComparator.compare(expected, actual, NAME_OF)).get("locations").match()).isTrue();
  }

  @Test
  void locations_missingOne_isAMismatch_renderedSorted() {
    SearchIntent expected = SearchIntent.builder()
        .locations(List.of(new LocationRef("Andheri East", null), new LocationRef("Andheri West", null))).build();
    SearchIntent actual = SearchIntent.builder().locations(List.of(new LocationRef("Andheri East", ANDHERI_E))).build();
    SlotResult slot = bySlot(IntentComparator.compare(expected, actual, NAME_OF)).get("locations");
    assertThat(slot.match()).isFalse();
    assertThat(slot.expected()).isEqualTo("andheri east, andheri west");
    assertThat(slot.actual()).isEqualTo("andheri east");
  }

  @Test
  void commutePlace_isCaseInsensitive_andMinutesCompareExactly() {
    SearchIntent expected = SearchIntent.builder().commuteTo(new CommuteTo("bkc", null, 20)).build();
    SearchIntent actual = SearchIntent.builder().commuteTo(new CommuteTo("BKC", UUID.randomUUID(), 30)).build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("commuteTo.place").match()).isTrue();
    assertThat(r.get("commuteTo.maxMinutes").match()).isFalse();
  }

  @Test
  void listSlots_nullEqualsEmpty_andIgnoreOrder() {
    SearchIntent expected = SearchIntent.builder().amenities(List.of("parking", "gym")).listingTypes(null).build();
    SearchIntent actual = SearchIntent.builder().amenities(List.of("Gym", "Parking")).listingTypes(List.of()).build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("amenities").match()).isTrue();
    assertThat(r.get("listingTypes").match()).isTrue();
    SearchIntent typed = SearchIntent.builder().listingTypes(List.of(ListingType.PRIVATE_ROOM)).build();
    assertThat(bySlot(IntentComparator.compare(typed, actual, NAME_OF)).get("listingTypes").actual()).isEqualTo("");
  }

  @Test
  void nestedSlots_areNullSafe() {
    SearchIntent expected = SearchIntent.builder()
        .bhk(new SearchIntent.BhkRange(2, 2))
        .lifestyle(SearchIntent.Lifestyle.builder().smoking("NO_SMOKERS").build()).build();
    SearchIntent actual = SearchIntent.builder().build();
    Map<String, SlotResult> r = bySlot(IntentComparator.compare(expected, actual, NAME_OF));
    assertThat(r.get("bhk.min").match()).isFalse();
    assertThat(r.get("bhk.max").expected()).isEqualTo("2");
    assertThat(r.get("lifestyle.smoking").match()).isFalse();
    assertThat(r.get("lifestyle.pets").match()).isTrue();
  }
}
