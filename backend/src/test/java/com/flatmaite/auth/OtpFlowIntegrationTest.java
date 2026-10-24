package com.flatmaite.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.config.FlatmaiteProperties;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phone sign-in end to end on the mock provider. Each test uses its own phone numbers and its own
 * client address, because the rate limiter's buckets outlive a single test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class OtpFlowIntegrationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired FlatmaiteProperties props;

  @BeforeEach
  void clearSends() {
    jdbc.update("DELETE FROM otp_sends");
  }

  @Test
  void theMockCode_fromTheBackendLog_signsIn(CapturedOutput output) {
    ResponseEntity<Map> sent = requestOtp("9800000001", "203.0.113.1");
    assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((String) data(sent).get("note")).contains("backend log");

    ResponseEntity<Map> verified = verifyOtp("9800000001", codeFromLog(output, "9800000001"));

    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(verified.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("fm_token=");
  }

  @Test
  void aWrongCode_isRejected() {
    requestOtp("9800000002", "203.0.113.2");

    assertThat(verifyOtp("9800000002", "000000").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void everySend_isCounted() {
    requestOtp("9800000003", "203.0.113.3");

    assertThat(sendsToday()).isEqualTo(1);
  }

  @Test
  void oneAddress_cyclingThroughNumbers_isCutOffAfterTen() {
    for (int i = 0; i < 10; i++) {
      assertThat(requestOtp("98100000%02d".formatted(i), "203.0.113.4").getStatusCode())
          .isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<Map> eleventh = requestOtp("9810000010", "203.0.113.4");
    assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(sendsToday()).isEqualTo(10);

    assertThat(requestOtp("9810000011", "203.0.113.5").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void theDailyCap_reached_refusesToSend_andPointsAtEmail() {
    int limit = props.getOtp().getDailySendLimit();
    jdbc.update(
        "INSERT INTO otp_sends (provider) SELECT 'mock' FROM generate_series(1, ?)", limit);

    ResponseEntity<Map> refused = requestOtp("9820000001", "203.0.113.6");

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(errorMessage(refused)).containsIgnoringCase("email");
    assertThat(sendsToday()).isEqualTo(limit);
  }

  @Test
  void yesterdaysSends_doNotCountTowardsTodaysCap() {
    int limit = props.getOtp().getDailySendLimit();
    jdbc.update(
        "INSERT INTO otp_sends (provider, created_at)"
            + " SELECT 'mock', date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'"
            + " - interval '1 second' FROM generate_series(1, ?)",
        limit);

    assertThat(requestOtp("9820000002", "203.0.113.7").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  // --- helpers ---

  private ResponseEntity<Map> requestOtp(String phone, String clientIp) {
    HttpHeaders headers = jsonHeaders();
    headers.add("X-Forwarded-For", clientIp);
    return rest.postForEntity(
        "/api/v1/auth/otp/request", new HttpEntity<>(Map.of("phone", phone), headers), Map.class);
  }

  private ResponseEntity<Map> verifyOtp(String phone, String otp) {
    return rest.postForEntity(
        "/api/v1/auth/otp/verify",
        new HttpEntity<>(Map.of("phone", phone, "otp", otp), jsonHeaders()),
        Map.class);
  }

  private int sendsToday() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM otp_sends"
            + " WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'",
        Integer.class);
  }

  /** The mock's contract is that the code is in the backend log; read it from there. */
  private static String codeFromLog(CapturedOutput output, String phone) {
    Matcher m = Pattern.compile("\\[MOCK SMS] OTP for \\+91" + phone + ": (\\d{6})").matcher(output.getOut());
    String code = null;
    while (m.find()) {
      code = m.group(1);
    }
    assertThat(code).as("mock OTP for %s in the log", phone).isNotNull();
    return code;
  }

  private static Map<?, ?> data(ResponseEntity<Map> response) {
    return (Map<?, ?>) response.getBody().get("data");
  }

  private static String errorMessage(ResponseEntity<Map> response) {
    return (String) ((Map<?, ?>) response.getBody().get("error")).get("message");
  }

  private static HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
