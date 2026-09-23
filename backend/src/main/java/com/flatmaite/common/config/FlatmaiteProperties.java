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
    /** A named home locality also admits every locality within this many kilometres. */
    private double nearbyRadiusKm = 5.0;

    /** Inside this distance a row is labelled "very close" rather than given a figure. */
    private double closeRadiusKm = 2.0;

    /** The ring searched after the user explicitly raises their budget. */
    private double escalationRadiusKm = 5.0;

    /** Below this many listings, the fallback ladder tops the page up. */
    private int minResults = 6;
  }

  /** Per-city commute calibration. Mumbai's numbers are the default for any city not listed. */
  @Getter
  @Setter
  public static class Geo {
    private static final Calibration MUMBAI = new Calibration(1.4, 20.0, 8);

    /** city name (case-insensitive) → calibration; Mumbai's values when absent or unknown. */
    private Map<String, Calibration> calibration = new LinkedHashMap<>();

    /**
     * Per-city commute calibration, falling back to the Mumbai constants for an unknown or null
     * {@code city}. This fallback is deliberate: the result feeds arithmetic (a distance-to-minutes
     * conversion) that must produce some number regardless of which city is asked about (spec
     * §4.10 point 3) — there is no such thing as "no commute estimate" for a listing that exists.
     *
     * <p>Its intentional counterpart is {@code boundsFor(String)}, which for the exact same
     * unknown-or-null input deliberately does NOT fall back — it returns empty instead. Scoping a
     * search cannot borrow another city's bounding box without silently placing a Bangalore user
     * in Mumbai (§4.11), so that method's "no answer" is the correct answer where this method's
     * "Mumbai's answer" is the correct answer here.
     *
     * <p>Making the two consistent — either by having this method return empty too, or by having
     * {@code boundsFor(String)} fall back to Mumbai's box — is the bug, not the fix.
     */
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
