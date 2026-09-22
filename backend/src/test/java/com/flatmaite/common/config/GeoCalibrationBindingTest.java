package com.flatmaite.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties.Calibration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * That {@code flatmaite.geo.calibration.*} actually binds. Spec §4.10 point 3 moved Mumbai's road
 * constants out of static finals and into per-city configuration so that a second city can be
 * added with a config entry rather than a code change — but nothing exercised the binding, and a
 * {@code Map<String, Calibration>} onto a <em>record</em> takes Spring's value-object path rather
 * than the setter path the rest of these properties use. An unbound map fails silently: every
 * lookup falls through to {@link FlatmaiteProperties.Geo#calibrationFor}'s Mumbai default, which
 * is exactly what the configuration was introduced to stop being the only answer.
 *
 * <p>No database and no web server — this is Spring's binder under test, not the app.
 */
class GeoCalibrationBindingTest {

  @Configuration
  @EnableConfigurationProperties(FlatmaiteProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void aCityEntryBinds_andIsFoundCaseInsensitively() {
    runner
        .withPropertyValues(
            "flatmaite.geo.calibration.pune.road-circuity=1.2",
            "flatmaite.geo.calibration.pune.speed-kmph=28.0",
            "flatmaite.geo.calibration.pune.overhead-min=5")
        .run(
            ctx -> {
              FlatmaiteProperties.Geo geo = ctx.getBean(FlatmaiteProperties.class).getGeo();

              assertThat(geo.calibrationFor("Pune")).isEqualTo(new Calibration(1.2, 28.0, 5));
              assertThat(geo.calibrationFor("pune")).isEqualTo(new Calibration(1.2, 28.0, 5));
              // an unlisted city, and a null one, still get a number to do arithmetic with
              assertThat(geo.calibrationFor("Mumbai")).isEqualTo(new Calibration(1.4, 20.0, 8));
              assertThat(geo.calibrationFor(null)).isEqualTo(new Calibration(1.4, 20.0, 8));
            });
  }

  @Test
  void anExplicitMumbaiEntryOverridesTheBuiltInDefault() {
    // the defaults are a fallback, not a floor: a deployment that re-measures Mumbai must be able
    // to say so, and see the new numbers actually used
    runner
        .withPropertyValues(
            "flatmaite.geo.calibration.mumbai.road-circuity=1.6",
            "flatmaite.geo.calibration.mumbai.speed-kmph=18.0",
            "flatmaite.geo.calibration.mumbai.overhead-min=10")
        .run(
            ctx -> {
              FlatmaiteProperties.Geo geo = ctx.getBean(FlatmaiteProperties.class).getGeo();
              assertThat(geo.calibrationFor("Mumbai")).isEqualTo(new Calibration(1.6, 18.0, 10));
            });
  }

  @Test
  void noEntriesAtAll_isTheMumbaiDefault_notACrash() {
    runner.run(
        ctx -> {
          FlatmaiteProperties.Geo geo = ctx.getBean(FlatmaiteProperties.class).getGeo();
          assertThat(geo.calibrationFor("Bangalore")).isEqualTo(new Calibration(1.4, 20.0, 8));
        });
  }
}
