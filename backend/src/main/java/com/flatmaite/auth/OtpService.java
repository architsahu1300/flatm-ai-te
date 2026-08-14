package com.flatmaite.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Mock OTP provider: generates a code, "sends" it by logging. A real SMS provider implements the
 * same request/verify surface. In dev the code also appears in the backend log.
 */
@Service
@Slf4j
public class OtpService {

  private final SecureRandom random = new SecureRandom();
  private final Cache<String, String> codes =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(50_000).build();

  public void request(String phone) {
    String code = "%06d".formatted(random.nextInt(1_000_000));
    codes.put(phone, code);
    log.info("[MOCK SMS] OTP for +91{}: {}", phone, code);
  }

  public boolean verify(String phone, String otp) {
    String expected = codes.getIfPresent(phone);
    if (expected != null && expected.equals(otp)) {
      codes.invalidate(phone);
      return true;
    }
    return false;
  }
}
