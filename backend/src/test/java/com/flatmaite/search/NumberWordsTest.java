package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NumberWordsTest {

  @Test
  void spelledOutThousands() {
    assertThat(NumberWords.parse("twenty five thousand")).hasValue(25000);
    assertThat(NumberWords.parse("thirty thousand")).hasValue(30000);
    assertThat(NumberWords.parse("fifteen thousand")).hasValue(15000);
  }

  @Test
  void lakhsIncludingFractions() {
    assertThat(NumberWords.parse("one lakh")).hasValue(100000);
    assertThat(NumberWords.parse("one and a half lakh")).hasValue(150000);
    assertThat(NumberWords.parse("1.5 lakh")).hasValue(150000);
    assertThat(NumberWords.parse("two lakhs")).hasValue(200000);
  }

  @Test
  void smallNumbersAndHundreds() {
    assertThat(NumberWords.parse("thirty")).hasValue(30);
    assertThat(NumberWords.parse("five hundred")).hasValue(500);
    assertThat(NumberWords.parse("twelve")).hasValue(12);
  }

  @Test
  void ignoresFillerWordsAndCurrency() {
    assertThat(NumberWords.parse("rs twenty thousand rupees")).hasValue(20000);
  }

  @Test
  void nonNumbers_areEmpty() {
    assertThat(NumberWords.parse("hello world")).isEmpty();
    assertThat(NumberWords.parse("")).isEmpty();
    assertThat(NumberWords.parse(null)).isEmpty();
    assertThat(NumberWords.parse("and a")).isEmpty();
  }

  @Test
  void numberRun_findsSpelledOutAmountsInsideASentence() {
    var m = NumberWords.NUMBER_RUN.matcher("a room for twenty five thousand in malad");

    assertThat(m.find()).isTrue();
    assertThat(m.group().trim()).isEqualTo("twenty five thousand");
  }

  @Test
  void numberRun_ignoresFillerOnlyRunsAndBareCounts() {
    assertThat(NumberWords.NUMBER_RUN.matcher("a room and a balcony").find()).isFalse();
    assertThat(NumberWords.NUMBER_RUN.matcher("need a 2 bedroom apartment").find()).isFalse();
    assertThat(NumberWords.NUMBER_RUN.matcher("get me a 3 bhk flat in bandra").find()).isFalse();
    assertThat(NumberWords.NUMBER_RUN.matcher("between 20k and 30k").find()).isFalse();
  }

  @Test
  void numberRun_stillFindsRealAmounts_withLeadingFillers() {
    var lakh = NumberWords.NUMBER_RUN.matcher("flat around 1.5 lakh please");
    assertThat(lakh.find()).isTrue();
    assertThat(lakh.group().trim()).isEqualTo("1.5 lakh");

    var thousand = NumberWords.NUMBER_RUN.matcher("for a thousand rupees");
    assertThat(thousand.find()).isTrue();
    assertThat(NumberWords.parse(thousand.group())).hasValue(1000);

    assertThat(NumberWords.parse("one lakh twenty thousand")).hasValue(120000);
  }
}
