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
  void thirtyEightLocalities_withUniqueNames() {
    assertThat(SeedLocalities.ALL).hasSize(38);
    assertThat(SeedLocalities.ALL.stream().map(SeedLocalities.Seed::name).distinct()).hasSize(38);
  }

  @Test
  void ids_areTheSeedRunnersHistoricalFormula() {
    UUID expected = UUID.nameUUIDFromBytes("flatmaite:locality:Powai".getBytes(StandardCharsets.UTF_8));
    assertThat(SeedLocalities.id("Powai")).isEqualTo(expected);
  }

  @Test
  void entities_carryNameAliasesCoordinatesAndDeterministicIds() {
    List<Locality> all = SeedLocalities.entities();
    assertThat(all).hasSize(38);
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
