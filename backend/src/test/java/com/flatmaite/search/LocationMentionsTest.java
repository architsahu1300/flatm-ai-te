package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LocationMentionsTest {

  private LocalityResolver resolver;

  private static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(List.of(locality("Andheri East", "andheri"), locality("BKC"), locality("Powai"), locality("Malad")));
    resolver = new LocalityResolver(repo);
    resolver.load();
  }

  private LocationMentions mentions(String text) {
    return LocationMentions.from(Tokens.of(text), resolver.scan(text));
  }

  @Test
  void plainMention_isHome() {
    LocationMentions m = mentions("private room in andheri");

    assertThat(m.home()).extracting(LocalityResolver.Match::canonicalName).containsExactly("Andheri East");
    assertThat(m.exclude()).isEmpty();
    assertThat(m.commute()).isEmpty();
  }

  @Test
  void liveHereWorkThere_splitsHomeAndCommute() {
    LocationMentions m = mentions("room in andheri, i work at bkc");

    assertThat(m.home()).extracting(LocalityResolver.Match::canonicalName).containsExactly("Andheri East");
    assertThat(m.commute()).map(LocalityResolver.Match::canonicalName).hasValue("BKC");
  }

  @Test
  void commuteCues() {
    assertThat(mentions("near bkc").commute()).isPresent();
    assertThat(mentions("close to bkc").commute()).isPresent();
    assertThat(mentions("office in bkc").commute()).isPresent();
    assertThat(mentions("within 20 min of bkc").commute()).isPresent();
    assertThat(mentions("flat in bkc").commute()).isEmpty();
  }

  @Test
  void minutesFrom_isACommuteCue() {
    // "15 min from powai" — the reversed phrasing the forward cue window does not cover
    LocationMentions m = mentions("15 min from powai");

    assertThat(m.commute()).map(LocalityResolver.Match::canonicalName).hasValue("Powai");
    assertThat(m.home()).isEmpty();
  }

  @Test
  void negationCues_exclude() {
    assertThat(mentions("anywhere but andheri").exclude()).hasSize(1);
    assertThat(mentions("not in andheri").exclude()).hasSize(1);
    assertThat(mentions("except andheri").exclude()).hasSize(1);
    assertThat(mentions("avoid andheri").exclude()).hasSize(1);
    assertThat(mentions("other than andheri").exclude()).hasSize(1);
    assertThat(mentions("andheri nahi").exclude()).hasSize(1);
    assertThat(mentions("anywhere but andheri").home()).isEmpty();
  }

  @Test
  void aNegatedWordFurtherBack_doesNotExcludeThePlace() {
    // "no" is three tokens before "andheri" and applies to smokers, not the locality
    LocationMentions m = mentions("no smokers in andheri");

    assertThat(m.home()).hasSize(1);
    assertThat(m.exclude()).isEmpty();
  }

  @Test
  void onlyTheFirstCommuteCuedMention_isTheAnchor() {
    LocationMentions m = mentions("near bkc or near powai, room in malad");

    assertThat(m.commute()).map(LocalityResolver.Match::canonicalName).hasValue("BKC");
    assertThat(m.home()).extracting(LocalityResolver.Match::canonicalName).containsExactly("Powai", "Malad");
  }
}
