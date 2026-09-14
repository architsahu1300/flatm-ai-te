package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrigramsTest {

  @Test
  void identicalStrings_scoreOne() {
    assertThat(Trigrams.similarity("powai", "powai")).isEqualTo(1.0);
  }

  @Test
  void insertionTypo_clearsTheThreshold() {
    // "  powaii " vs "  powai ": 5 shared of 8 distinct trigrams
    assertThat(Trigrams.similarity("powaii", "powai")).isGreaterThanOrEqualTo(LocalityResolver.FUZZY_THRESHOLD);
    assertThat(Trigrams.similarity("malaad", "malad")).isGreaterThanOrEqualTo(LocalityResolver.FUZZY_THRESHOLD);
  }

  @Test
  void commonWordNearAPlaceName_staysBelowTheThreshold() {
    // "world" vs "worli" shares 4 of 8 — must not become a Worli filter
    assertThat(Trigrams.similarity("world", "worli")).isLessThan(LocalityResolver.FUZZY_THRESHOLD);
  }

  @Test
  void disjointStrings_scoreZero() {
    assertThat(Trigrams.similarity("abc", "xyz")).isEqualTo(0.0);
    assertThat(Trigrams.similarity("", "powai")).isEqualTo(0.0);
  }
}
