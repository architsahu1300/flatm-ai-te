package com.flatmaite.common.config;

import java.math.BigDecimal;
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
  private String frontendUrl = "http://localhost:3000";

  @Getter
  @Setter
  public static class Jwt {
    private String secret;
    private int ttlHours = 168;
    private String cookieName = "fm_token";
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
}
