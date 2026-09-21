package com.flatmaite.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The gazetteer is shared by the seed and the offline eval; its identity must not drift. */
class SeedLocalitiesTest {

  @Test
  void theGazetteerCoversFiftyNineMumbaiLocalities() {
    assertThat(SeedLocalities.ALL).hasSize(59);
    assertThat(SeedLocalities.ALL.stream().map(SeedLocalities.Seed::name).distinct()).hasSize(59);
    assertThat(SeedLocalities.ALL).allSatisfy(s -> assertThat(s.city()).isEqualTo("Mumbai"));
  }

  @Test
  void theLocalitiesTheReportedBugNeeded() {
    List<String> names = SeedLocalities.ALL.stream().map(SeedLocalities.Seed::name).toList();
    assertThat(names).contains("Kandivali", "Borivali", "Dahisar", "Mira Road", "Versova");
  }

  @Test
  void everyCentroidIsInsideTheMumbaiMetropolitanRegion() {
    assertThat(SeedLocalities.ALL)
        .allSatisfy(
            s -> {
              assertThat(s.lat()).isBetween(18.85, 19.35);
              assertThat(s.lng()).isBetween(72.75, 73.15);
            });
  }

  @Test
  void ids_areTheSeedRunnersHistoricalFormula() {
    UUID expected = UUID.nameUUIDFromBytes("flatmaite:locality:Powai".getBytes(StandardCharsets.UTF_8));
    assertThat(SeedLocalities.id("Powai")).isEqualTo(expected);
  }

  @Test
  void entities_carryNameAliasesCoordinatesAndDeterministicIds() {
    List<Locality> all = SeedLocalities.entities();
    assertThat(all).hasSize(59);
    Locality bkc = all.stream().filter(l -> l.getName().equals("BKC")).findFirst().orElseThrow();
    assertThat(bkc.getId()).isEqualTo(SeedLocalities.id("BKC"));
    assertThat(bkc.getAliases()).contains("bandra kurla complex");
    assertThat(bkc.getLat()).isEqualTo(19.0653);
    assertThat(bkc.getLng()).isEqualTo(72.8693);
  }

  @Test
  void bothAndheris_aliasTheBareName() {
    long andheris =
        SeedLocalities.entities().stream()
            .filter(l -> List.of(l.getAliases()).contains("andheri"))
            .count();
    assertThat(andheris).isEqualTo(2);
  }

  @Test
  void entities_areFreshInstancesEachCall() {
    assertThat(SeedLocalities.entities().get(0)).isNotSameAs(SeedLocalities.entities().get(0));
  }
}
