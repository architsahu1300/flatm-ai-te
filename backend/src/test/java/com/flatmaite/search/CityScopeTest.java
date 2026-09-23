package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CityScopeTest {

  @Test
  void anAbsentCityIsAStateNotADefault() {
    CityScope unset = CityScope.unset();

    assertThat(unset.source()).isEqualTo(CityScope.Source.UNSET);
    assertThat(unset.city()).isNull();
    assertThat(unset.isSet()).isFalse();
  }

  @Test
  void aProfileCityIsCarriedAsItself() {
    CityScope scope = CityScope.of("Mumbai");

    assertThat(scope.source()).isEqualTo(CityScope.Source.PROFILE);
    assertThat(scope.city()).isEqualTo("Mumbai");
  }

  @Test
  void aBlankCityIsUnsetRatherThanAnEmptyString() {
    assertThat(CityScope.of("  ").source()).isEqualTo(CityScope.Source.UNSET);
    assertThat(CityScope.of(null).source()).isEqualTo(CityScope.Source.UNSET);
  }
}
