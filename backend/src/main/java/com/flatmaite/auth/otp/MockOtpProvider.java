package com.flatmaite.auth.otp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates a code and "sends" it by logging. Free and offline, so it is the default and what the
 * tests run against. Codes live in memory: a restart between send and verify means asking again.
 */
@Slf4j
public class MockOtpProvider implements OtpProvider {

  public static final String NAME = "mock";

  private final SecureRandom random = new SecureRandom();
  private final Cache<String, String> codes =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(50_000).build();

  @Override
  public void send(String phone) {
    String code = "%06d".formatted(random.nextInt(1_000_000));
    codes.put(phone, code);
    log.info("[MOCK SMS] OTP for +91{}: {}", phone, code);
  }

  @Override
  public boolean verify(String phone, String code) {
    String expected = codes.getIfPresent(phone);
    if (expected != null && expected.equals(code)) {
      codes.invalidate(phone);
      return true;
    }
    return false;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String note() {
    return "Mock provider — code is in the backend log";
  }
}
