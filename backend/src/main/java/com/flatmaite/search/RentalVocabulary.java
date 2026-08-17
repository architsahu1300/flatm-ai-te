package com.flatmaite.search;

import com.flatmaite.common.domain.RoomType;

/**
 * Indian rental vocabulary, in one place, because two consumers must agree: the LLM prompt (which
 * embeds {@link #GLOSSARY} verbatim) and {@link KeywordIntentParser} (the keyless fallback). When
 * they drift, the same query yields different intent depending on whether the LLM happened to be
 * reachable.
 *
 * <p>The subtlety that makes this necessary: in Mumbai "sharing" counts people per ROOM, not per
 * flat. "Single sharing" means one person in the room — i.e. a PRIVATE room — even though the word
 * "sharing" appears. Matching bare "sharing" first gets this exactly backwards.
 */
public final class RentalVocabulary {

  private RentalVocabulary() {}

  public static final String GLOSSARY =
      """
      Indian/Mumbai rental vocabulary. "sharing" counts people per ROOM, not per flat:
      - "single sharing", "single occupancy", "1 sharing", "no sharing", "single share"
        = one person in the room -> roomType PRIVATE (even though it says "sharing")
      - "double sharing", "twin sharing", "2 sharing", "triple sharing", "3 sharing"
        = several people in one room -> roomType SHARED
      - bare "sharing" or "shared room" with no number -> SHARED
      - "own room", "private room", "separate room", "independent room" -> PRIVATE
      - "1RK", "studio", "entire/whole/full flat", "nBHK for myself" -> ENTIRE
      - A room inside an "nBHK" is still PRIVATE or SHARED per the words above; "2BHK" only sets bhk.
      - "PG"/"paying guest": PRIVATE when "single" is mentioned, otherwise SHARED.
      - Amounts: "25k"=25000, "1 lakh"/"1L"=100000, and spelled-out amounts count too
        ("twenty five thousand"=25000, "thirty thousand"=30000).
      """;

  private static final String[] SINGLE_OCCUPANCY = {
    "single sharing", "single-sharing", "single occupancy", "single share",
    "1 sharing", "one sharing", "no sharing", "single sharing basis",
  };

  private static final String[] MULTI_OCCUPANCY = {
    "double sharing", "twin sharing", "double occupancy", "2 sharing", "two sharing",
    "triple sharing", "3 sharing", "three sharing", "shared room", "sharing basis",
    "on sharing", "sharing",
  };

  private static final String[] PRIVATE_WORDS = {
    "private room", "own room", "separate room", "independent room",
  };

  private static final String[] ENTIRE_WORDS = {
    "1rk", "1 rk", "studio", "entire flat", "whole flat", "full flat", "entire apartment",
  };

  /**
   * Room type stated explicitly by occupancy vocabulary, or null when the query says nothing
   * decisive (callers then fall back to shape heuristics like nBHK). Order is load-bearing:
   * single-occupancy phrasings must win over the bare "sharing" match.
   */
  public static RoomType explicitRoomType(String lowercaseQuery) {
    if (containsAny(lowercaseQuery, SINGLE_OCCUPANCY)) {
      return RoomType.PRIVATE;
    }
    if (containsAny(lowercaseQuery, MULTI_OCCUPANCY)) {
      return RoomType.SHARED;
    }
    if (containsAny(lowercaseQuery, PRIVATE_WORDS)) {
      return RoomType.PRIVATE;
    }
    if (containsAny(lowercaseQuery, ENTIRE_WORDS)) {
      return RoomType.ENTIRE;
    }
    return null;
  }

  private static boolean containsAny(String q, String[] needles) {
    for (String needle : needles) {
      if (q.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
