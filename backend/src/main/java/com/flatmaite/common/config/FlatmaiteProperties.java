package com.flatmaite.common.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flatmaite")
@Getter
@Setter
public class FlatmaiteProperties {

  private Jwt jwt = new Jwt();
  private Ai ai = new Ai();
  private Google google = new Google();
  private Storage storage = new Storage();
  private Search search = new Search();
  private Geo geo = new Geo();
  private String frontendUrl = "http://localhost:3000";

  /** Per-city travel calibration: road-circuity multiplier, effective speed, fixed overhead. */
  public record Calibration(double roadCircuity, double speedKmph, int overheadMin) {}

  @Getter
  @Setter
  public static class Jwt {
    private String secret;
    private int ttlHours = 168;
    private String cookieName = "fm_token";
    /** Must be true wherever the app is served over HTTPS; false only for local http dev. */
    private boolean secureCookie = false;
  }

  @Getter
  @Setter
  public static class Ai {
    /** auto | true | false — auto resolves to mock when no real OpenAI key is configured. */
    private String mock = "auto";

    private boolean explanationsEnabled = true;
    private int dailySearchLimit = 50;
    private BigDecimal dailyCostLimitUsd = new BigDecimal("0.50");
    private int anonDailySearchLimit = 10;
  }

  @Getter
  @Setter
  public static class Google {
    private String clientId = "";
    private String clientSecret = "";

    public boolean isConfigured() {
      return !clientId.isBlank() && !clientSecret.isBlank();
    }
  }

  @Getter
  @Setter
  public static class Storage {
    private String uploadDir = "./uploads";
  }

  @Getter
  @Setter
  public static class Search {
    /** A named home locality also admits every locality within this many estimated minutes. */
    private int nearbyRadiusMinutes = 25;

    /** Below this many listings, the pipeline tops the page up with nearby and near-miss results. */
    private int minResults = 6;

    /** The second ring the rescue ladder reaches for before it starts dropping filters. */
    private int rescueRadiusMinutes = 45;
  }

  /** Per-city commute calibration. Mumbai's numbers are the default for any city not listed. */
  @Getter
  @Setter
  public static class Geo {
    private static final Calibration MUMBAI = new Calibration(1.4, 20.0, 8);

    /** city name (case-insensitive) → calibration; Mumbai's values when absent or unknown. */
    private Map<String, Calibration> calibration = new LinkedHashMap<>();

    public Calibration calibrationFor(String city) {
      if (city == null) {
        return MUMBAI;
      }
      return calibration.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(city))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(MUMBAI);
    }
  }
}
