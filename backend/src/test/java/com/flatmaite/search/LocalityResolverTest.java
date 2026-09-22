package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.listing.PropertyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LocalityResolverTest {

  private LocalityResolver resolver;
  private LocalityRepository localities;
  private PropertyRepository properties;

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
    List<Locality> seedData =
        new ArrayList<>(
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
                locality("Worli"),
                // Present so anUnsetScopeStillResolvesAcrossEveryCity below exercises a real
                // Mumbai locality — the same name that motivated this whole workstream (see
                // task brief context: pre-Task-4, "Kandivali" resolved to nothing).
                locality("Kandivali")));
    // A mutable fake, not a fixed stub: the scoping tests below seed a second city straight
    // through the repository (never through SeedLocalities), then reload() must see it.
    localities = Mockito.mock(LocalityRepository.class);
    Mockito.when(localities.findAll()).thenAnswer(inv -> new ArrayList<>(seedData));
    Mockito.when(localities.save(Mockito.any(Locality.class)))
        .thenAnswer(
            inv -> {
              Locality l = inv.getArgument(0);
              if (l.getId() == null) {
                l.setId(UUID.randomUUID());
              }
              seedData.add(l);
              return l;
            });
    Mockito.when(localities.findById(Mockito.any(UUID.class)))
        .thenAnswer(
            inv -> {
              UUID wanted = inv.getArgument(0);
              return seedData.stream().filter(l -> wanted.equals(l.getId())).findFirst();
            });
    // Unstubbed: Mockito's default answer for a List-returning method is an empty list, so the
    // own-data ladder step always misses here — these tests exercise the gazetteer layers only.
    properties = Mockito.mock(PropertyRepository.class);
    resolver = new LocalityResolver(localities, properties);
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
    assertThat(resolver.nameOf(UUID.randomUUID())).isNull();
    assertThat(resolver.vocabulary())
        .contains("Powai (hiranandani)", "Kurla", "BKC (bandra kurla complex, bandra kurla)");
  }

  @Test
  void reload_picksUpLocalitiesAddedAfterStartup() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll()).thenReturn(List.of()).thenReturn(List.of(locality("Powai", "hiranandani")));
    LocalityResolver fresh = new LocalityResolver(repo, Mockito.mock(PropertyRepository.class));
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

  @Test
  void aScopedResolutionNeverCrossesACityBoundary() {
    localities.save(Locality.builder().name("MG Road").city("Bangalore").lat(12.97).lng(77.6).build());
    localities.save(Locality.builder().name("MG Road").city("Mumbai").lat(19.06).lng(72.83).build());
    resolver.reload();

    Placement mumbai = resolver.resolve("MG Road", CityScope.of("Mumbai"));

    assertThat(mumbai.source()).isEqualTo(Placement.Source.GAZETTEER);
    assertThat(mumbai.localityIds()).hasSize(1);
    assertThat(localities.findById(mumbai.localityIds().get(0)).orElseThrow().getCity())
        .isEqualTo("Mumbai");
  }

  @Test
  void fuzzyMatchingIsScopedToo() {
    localities.save(Locality.builder().name("Indiranagar").city("Bangalore").lat(12.97).lng(77.64).build());
    resolver.reload();

    // near-miss spelling, but the wrong city — must not resolve
    assertThat(resolver.resolve("indiranagr", CityScope.of("Mumbai")).source())
        .isEqualTo(Placement.Source.NONE);
  }

  @Test
  void anUnsetScopeStillResolvesAcrossEveryCity() {
    assertThat(resolver.resolve("Kandivali", CityScope.unset()).source())
        .isEqualTo(Placement.Source.GAZETTEER);
  }
}
