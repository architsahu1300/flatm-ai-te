package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LocalityResolverTest {

  private LocalityResolver resolver;

  static Locality locality(String name, String... aliases) {
    Locality l = Locality.builder().name(name).lat(19.0).lng(72.8).aliases(aliases).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes());
  }

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", "hiranandani"),
                locality("Goregaon", "goregaon east", "goregaon west"),
                locality("Andheri East", "andheri east", "andheri"),
                locality("Andheri West", "andheri west", "andheri"),
                locality("BKC", "bandra kurla complex", "bandra kurla"),
                locality("Bandra", "bandra west"),
                locality("Kurla"),
                locality("Sion"),
                locality("Parel", "parel"),
                locality("Lower Parel", "lower parel"),
                locality("Worli")));
    resolver = new LocalityResolver(repo);
    resolver.load();
  }

  @Test
  void longestMatchWins_soBandraKurlaComplexIsOnlyBkc() {
    List<LocalityResolver.Match> m = resolver.scan("a room near bandra kurla complex please");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("BKC"));
    assertThat(m.get(0).canonicalName()).isEqualTo("BKC");
    assertThat(m.get(0).tokenStart()).isEqualTo(3);
    assertThat(m.get(0).tokenEnd()).isEqualTo(6);
    assertThat(m.get(0).confidence()).isEqualTo(LocalityResolver.EXACT);
  }

  @Test
  void ambiguousAlias_yieldsEveryLocalityItNames() {
    List<LocalityResolver.Match> m = resolver.scan("flat in andheri");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactlyInAnyOrder(id("Andheri East"), id("Andheri West"));
    assertThat(m.get(0).canonicalName()).isEqualTo("Andheri East / Andheri West");
  }

  @Test
  void twoWordAlias_disambiguates() {
    List<LocalityResolver.Match> m = resolver.scan("andheri west please");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("Andheri West"));
  }

  @Test
  void wordBoundaries_mansionIsNotSion() {
    List<LocalityResolver.Match> m = resolver.scan("a mansion in sion");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("Sion"));
    assertThat(m.get(0).tokenStart()).isEqualTo(3);
  }

  @Test
  void parelAndLowerParel_areDistinctMatches() {
    List<LocalityResolver.Match> m = resolver.scan("lower parel or parel");

    assertThat(m).extracting(LocalityResolver.Match::canonicalName).containsExactly("Lower Parel", "Parel");
  }

  @Test
  void fuzzyLayer_correctsAnInsertionTypo_atLowerConfidence() {
    List<LocalityResolver.Match> m = resolver.scan("near powaii");

    assertThat(m).hasSize(1);
    assertThat(m.get(0).localityIds()).containsExactly(id("Powai"));
    assertThat(m.get(0).confidence()).isBetween(LocalityResolver.FUZZY_THRESHOLD, 0.99);
  }

  @Test
  void fuzzyLayer_ignoresShortTokensAndCommonWords() {
    assertThat(resolver.scan("pow wow")).isEmpty();
    assertThat(resolver.scan("best in the world")).isEmpty();
  }

  @Test
  void resolve_exactAliasWithoutSubstringGuessing() {
    assertThat(resolver.resolve("Bandra Kurla").map(LocalityResolver.Match::localityIds)).hasValue(List.of(id("BKC")));
    assertThat(resolver.resolve("bandra").map(LocalityResolver.Match::localityIds)).hasValue(List.of(id("Bandra")));
    assertThat(resolver.resolve("Goregaon").map(LocalityResolver.Match::confidence)).hasValue(LocalityResolver.EXACT);
  }

  @Test
  void resolve_fuzzyAndUnknown() {
    Optional<LocalityResolver.Match> fuzzy = resolver.resolve("powaii");
    assertThat(fuzzy).isPresent();
    assertThat(fuzzy.get().localityIds()).containsExactly(id("Powai"));
    assertThat(fuzzy.get().confidence()).isLessThan(LocalityResolver.CONFIDENT);

    assertThat(resolver.resolve("Atlantis")).isEmpty();
    assertThat(resolver.resolve("  ")).isEmpty();
    assertThat(resolver.resolve(null)).isEmpty();
  }

  @Test
  void nameOf_andVocabulary() {
    assertThat(resolver.nameOf(id("Powai"))).isEqualTo("Powai");
    assertThat(resolver.nameOf(UUID.randomUUID())).isEqualTo("Mumbai");
    assertThat(resolver.vocabulary())
        .contains("Powai (hiranandani)", "Kurla", "BKC (bandra kurla complex, bandra kurla)");
  }

  @Test
  void reload_picksUpLocalitiesAddedAfterStartup() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(List.of()).thenReturn(List.of(locality("Powai", "hiranandani")));
    LocalityResolver fresh = new LocalityResolver(repo);
    fresh.load();
    assertThat(fresh.resolve("powai")).isEmpty();

    fresh.reload();

    assertThat(fresh.resolve("powai")).isPresent();
    assertThat(fresh.vocabulary()).containsExactly("Powai (hiranandani)");
  }

  @Test
  void version_increasesOnReload_soDerivedCachesCanInvalidate() {
    long v0 = resolver.version();

    resolver.reload();

    assertThat(resolver.version()).isGreaterThan(v0);
  }
}
