package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokensTest {

  @Test
  void lowercasesAndSplitsOnNonLettersOrDigits() {
    List<Tokens.Token> t = Tokens.of("Room in Andheri-West, 25k!");

    assertThat(t).extracting(Tokens.Token::text)
        .containsExactly("room", "in", "andheri", "west", "25k");
  }

  @Test
  void spansIndexTheLowercasedText() {
    List<Tokens.Token> t = Tokens.of("BKC room");

    assertThat(t.get(0).start()).isEqualTo(0);
    assertThat(t.get(0).end()).isEqualTo(3);
    assertThat(t.get(1).start()).isEqualTo(4);
    assertThat(t.get(1).end()).isEqualTo(8);
  }

  @Test
  void keepsUnicodeLetters() {
    assertThat(Tokens.of("पवई room")).extracting(Tokens.Token::text).containsExactly("पवई", "room");
  }

  @Test
  void nullAndBlank_yieldNoTokens() {
    assertThat(Tokens.of(null)).isEmpty();
    assertThat(Tokens.of("  ,, ")).isEmpty();
  }

  @Test
  void phraseJoinsARangeWithSingleSpaces() {
    List<Tokens.Token> t = Tokens.of("near bandra kurla complex please");

    assertThat(Tokens.phrase(t, 1, 4)).isEqualTo("bandra kurla complex");
  }
}
