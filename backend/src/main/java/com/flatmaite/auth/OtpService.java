package com.flatmaite.auth;

import com.flatmaite.auth.otp.OtpProvider;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.web.ApiException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Phone sign-in codes: the daily send cap, then whichever {@link OtpProvider} is configured.
 *
 * <p>The cap is counted in the database, not memory, because it is the control that protects real
 * money and Render's free tier restarts the app every time it wakes. Checking before the send and
 * recording after can overshoot by the requests in flight at the boundary — a handful, not a leak.
 * The per-number and per-address rate limits sit in {@link AuthController} with the other auth
 * limits.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

  private final OtpProvider provider;
  private final JdbcTemplate jdbc;
  private final FlatmaiteProperties props;

  public void request(String phone) {
    if (sendsToday() >= props.getOtp().getDailySendLimit()) {
      throw ApiException.tooManyRequests(
          "Phone sign-in has reached today's limit — sign in with email, or try again tomorrow.");
    }
    provider.send(phone);
    jdbc.update("INSERT INTO otp_sends (provider) VALUES (?)", provider.name());
  }

  public boolean verify(String phone, String otp) {
    return provider.verify(phone, otp);
  }

  /** Shown under the code box after a send, or null. */
  public String note() {
    return provider.note();
  }

  private int sendsToday() {
    Timestamp dayStart =
        Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC));
    Integer sends =
        jdbc.queryForObject(
            "SELECT count(*) FROM otp_sends WHERE created_at >= ?", Integer.class, dayStart);
    return sends == null ? 0 : sends;
  }
}
