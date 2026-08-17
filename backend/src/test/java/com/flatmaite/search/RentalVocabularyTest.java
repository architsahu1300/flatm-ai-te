package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.RoomType;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/**
 * Golden set for Indian rental phrasing. These cases are the ones real users type and that a naive
 * substring match gets wrong — most importantly "single sharing", which means one person per room
 * (PRIVATE) despite containing the word "sharing".
 */
class RentalVocabularyTest {

  @ParameterizedTest(name = "\"{0}\" -> {1}")
  @DisplayName("occupancy vocabulary maps to the right room type")
  @CsvSource({
    // the reported bug: "sharing" with a count of one means a room to yourself
    "single sharing room in a 2bhk in goregaon with a budget of 25000, PRIVATE",
    "single occupancy pg in andheri, PRIVATE",
    "1 sharing room near bkc, PRIVATE",
    "no sharing please, PRIVATE",
    // multi-occupancy really is shared
    "double sharing room in powai, SHARED",
    "twin sharing bed in a hostel, SHARED",
    "triple sharing room under 8k, SHARED",
    "3 sharing room in malad, SHARED",
    "shared room in bandra, SHARED",
    "room on sharing basis, SHARED",
    // plain private phrasings
    "private room in a 3bhk, PRIVATE",
    "looking for my own room in worli, PRIVATE",
    // whole-place phrasings
    "1rk in dadar, ENTIRE",
    "studio apartment in lower parel, ENTIRE",
    "entire flat for my family, ENTIRE",
  })
  void mapsOccupancyPhrasing(String query, RoomType expected) {
    assertThat(RentalVocabulary.explicitRoomType(query)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "\"{0}\" -> no explicit room type")
  @DisplayName("queries without occupancy vocabulary defer to shape heuristics")
  @CsvSource({"2bhk in goregaon under 40k", "flat in andheri", "quiet vegetarian household"})
  void leavesShapeHeuristicsAlone(String query) {
    assertThat(RentalVocabulary.explicitRoomType(query)).isNull();
  }

  @ParameterizedTest(name = "keyword fallback: \"{0}\" -> {1}")
  @DisplayName("the keyless fallback parser agrees with the vocabulary")
  @CsvSource({
    "single sharing room in a 2bhk in goregaon with a budget of 25000, PRIVATE",
    "double sharing room in powai, SHARED",
    "private room near bkc under 25k, PRIVATE",
  })
  void fallbackParserAgrees(String query, RoomType expected) {
    // no locality data needed: these assertions are about room type only
    LocalityRepository localities = Mockito.mock(LocalityRepository.class);
    Mockito.when(localities.findAll()).thenReturn(List.of());
    LocalityResolver resolver = new LocalityResolver(localities);
    resolver.load();

    SearchIntent intent = new KeywordIntentParser(resolver).parse(query);
    assertThat(intent.roomType()).isEqualTo(expected);
  }
}
