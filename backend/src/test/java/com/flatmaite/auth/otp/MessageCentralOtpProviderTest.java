package com.flatmaite.auth.otp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.web.ApiException;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** Message Central's HTTP contract, against a fake server: no network, no credit spent. */
class MessageCentralOtpProviderTest {

  private static final String BASE = "https://mc.test";
  private static final String PHONE = "9876543210";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private MockRestServiceServer server;
  private MessageCentralOtpProvider provider;

  @BeforeEach
  void setUp() {
    provider = providerWith("secret");
  }

  @Test
  void send_fetchesATokenThenAsksForASixDigitSms() {
    String token = expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("authToken", token))
        .andExpect(queryParam("customerId", "C-42"))
        .andExpect(queryParam("countryCode", "91"))
        .andExpect(queryParam("flowType", "SMS"))
        .andExpect(queryParam("mobileNumber", PHONE))
        .andExpect(queryParam("otpLength", "6"))
        .andRespond(sent("vid-1"));

    provider.send(PHONE);

    server.verify();
  }

  @Test
  void token_carriesThePasswordBase64Encoded_withThePlusSignEscaped() {
    // "a?>" encodes to "YT8+"; an unescaped '+' would reach them as a space
    String key = Base64.getEncoder().encodeToString("a?>".getBytes(UTF_8));
    assertThat(key).contains("+");
    provider = providerWith("a?>");
    server
        .expect(requestTo(startsWith(BASE + "/auth/v1/authentication/token?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(requestTo(containsString("key=" + URLEncoder.encode(key, UTF_8))))
        .andExpect(queryParam("customerId", "C-42"))
        .andExpect(queryParam("scope", "NEW"))
        .andExpect(queryParam("country", "91"))
        .andRespond(tokenReply(jwt(clock.instant().plus(Duration.ofHours(1)))));
    server.expect(requestTo(startsWith(BASE + "/verification/v3/send?"))).andRespond(sent("vid-1"));

    provider.send(PHONE);

    server.verify();
  }

  @Test
  void verify_checksTheCodeAgainstTheVerificationIdFromSend() {
    String token = sendOnce("vid-7");
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("authToken", token))
        .andExpect(queryParam("verificationId", "vid-7"))
        .andExpect(queryParam("code", "123456"))
        .andRespond(validated());

    assertThat(provider.verify(PHONE, "123456")).isTrue();
    server.verify();
  }

  @Test
  void verify_aMatchedCodeIsSpent() {
    sendOnce("vid-1");
    server.expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?"))).andRespond(validated());
    assertThat(provider.verify(PHONE, "123456")).isTrue();

    // no further request expected: the server fails verify() if one is made
    assertThat(provider.verify(PHONE, "123456")).isFalse();
    server.verify();
  }

  @Test
  void verify_withNoCodeSent_isFalseWithoutCallingThem() {
    assertThat(provider.verify(PHONE, "123456")).isFalse();
    server.verify();
  }

  @Test
  void verify_wrongCode_isFalse_andTheSameCodeStaysLiveForAnotherTry() {
    sendOnce("vid-1");
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?")))
        .andRespond(failure(HttpStatus.BAD_REQUEST, 702, "WRONG_OTP_PROVIDED"));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?")))
        .andExpect(queryParam("verificationId", "vid-1"))
        .andRespond(validated());

    assertThat(provider.verify(PHONE, "000000")).isFalse();
    assertThat(provider.verify(PHONE, "123456")).isTrue();
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {505, 700, 703, 705, 800})
  void verify_codeTheyNoLongerAccept_isFalse_andIsDropped(int responseCode) {
    sendOnce("vid-1");
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?")))
        .andRespond(failure(HttpStatus.OK, responseCode, "NOPE"));

    assertThat(provider.verify(PHONE, "123456")).isFalse();
    assertThat(provider.verify(PHONE, "123456")).isFalse();
    server.verify();
  }

  @Test
  void verify_successCodeWithoutACompletedStatus_failsClosed() {
    sendOnce("vid-1");
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?")))
        .andRespond(
            withSuccess(
                "{\"responseCode\":200,\"message\":\"SUCCESS\","
                    + "\"data\":{\"verificationStatus\":\"VERIFICATION_PENDING\"}}",
                MediaType.APPLICATION_JSON));

    assertThat(provider.verify(PHONE, "123456")).isFalse();
  }

  @Test
  void verify_whenTheyAreUnreachable_isServiceUnavailable_notAWrongCode() {
    sendOnce("vid-1");
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/validateOtp?")))
        .andRespond(withException(new IOException("connection reset")));

    assertUnavailable(() -> provider.verify(PHONE, "123456"));
  }

  @Test
  void send_whenACodeIsAlreadyOutstanding_isTooManyRequests() {
    expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(failure(HttpStatus.BAD_REQUEST, 506, "REQUEST_ALREADY_EXISTS"));

    assertThatThrownBy(() -> provider.send(PHONE))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void send_whenTheyFail_isServiceUnavailable() {
    expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(failure(HttpStatus.INTERNAL_SERVER_ERROR, 500, "SERVER_ERROR"));

    assertUnavailable(() -> provider.send(PHONE));
  }

  @Test
  void send_whenTheyAreUnreachable_isServiceUnavailable() {
    expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(withException(new IOException("timed out")));

    assertUnavailable(() -> provider.send(PHONE));
  }

  @Test
  void token_isReusedAcrossCalls() {
    expectToken(clock.instant().plus(Duration.ofHours(1)));
    server.expect(requestTo(startsWith(BASE + "/verification/v3/send?"))).andRespond(sent("vid-1"));
    server.expect(requestTo(startsWith(BASE + "/verification/v3/send?"))).andRespond(sent("vid-2"));

    provider.send(PHONE);
    provider.send("9123456780");

    server.verify();
  }

  @Test
  void token_isRefetchedShortlyBeforeItExpires() {
    expectToken(clock.instant().plus(Duration.ofMinutes(5)));
    server.expect(requestTo(startsWith(BASE + "/verification/v3/send?"))).andRespond(sent("vid-1"));
    provider.send(PHONE);
    server.verify();
    server.reset();

    clock.advance(Duration.ofMinutes(4).plusSeconds(30));
    String fresh = expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andExpect(header("authToken", fresh))
        .andRespond(sent("vid-2"));
    provider.send("9123456780");

    server.verify();
  }

  @Test
  void token_rejectedMidLife_isRefetched_andTheCallRetriedOnce() {
    expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    String fresh = expectToken(clock.instant().plus(Duration.ofHours(2)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andExpect(header("authToken", fresh))
        .andRespond(sent("vid-1"));

    provider.send(PHONE);

    server.verify();
  }

  @Test
  void token_rejectedAgainAfterARefetch_isServiceUnavailable() {
    expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    expectToken(clock.instant().plus(Duration.ofHours(2)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertUnavailable(() -> provider.send(PHONE));
    server.verify();
  }

  @Test
  void token_refusedForBadCredentials_isServiceUnavailable() {
    server
        .expect(requestTo(startsWith(BASE + "/auth/v1/authentication/token?")))
        .andRespond(failure(HttpStatus.UNAUTHORIZED, 501, "INVALID_CUSTOMER_ID"));

    assertUnavailable(() -> provider.send(PHONE));
  }

  // --- helpers ---

  private MessageCentralOtpProvider providerWith(String password) {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    FlatmaiteProperties.MessageCentral config = new FlatmaiteProperties.MessageCentral();
    config.setBaseUrl(BASE);
    config.setCustomerId("C-42");
    config.setPassword(password);
    return new MessageCentralOtpProvider(builder, config, clock);
  }

  /**
   * A send that succeeds with {@code verificationId}; returns the token it used. The fake server
   * takes no new expectations once it has seen a request, so it is checked and cleared here.
   */
  private String sendOnce(String verificationId) {
    String token = expectToken(clock.instant().plus(Duration.ofHours(1)));
    server
        .expect(requestTo(startsWith(BASE + "/verification/v3/send?")))
        .andRespond(sent(verificationId));
    provider.send(PHONE);
    server.verify();
    server.reset();
    return token;
  }

  private String expectToken(Instant expiresAt) {
    String token = jwt(expiresAt);
    server
        .expect(requestTo(startsWith(BASE + "/auth/v1/authentication/token?")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(tokenReply(token));
    return token;
  }

  /** The shape of their token: a JWT whose payload carries {@code exp} in epoch seconds. */
  private static String jwt(Instant expiresAt) {
    String payload = "{\"sub\":\"C-42\",\"exp\":" + expiresAt.getEpochSecond() + "}";
    return "eyJhbGciOiJIUzUxMiJ9."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(UTF_8))
        + ".signature";
  }

  private static ResponseCreator tokenReply(String token) {
    return withSuccess("{\"status\":200,\"token\":\"" + token + "\"}", MediaType.APPLICATION_JSON);
  }

  private static ResponseCreator sent(String verificationId) {
    return withSuccess(
        "{\"responseCode\":200,\"message\":\"SUCCESS\",\"data\":{\"verificationId\":\""
            + verificationId
            + "\",\"mobileNumber\":\""
            + PHONE
            + "\",\"responseCode\":\"200\",\"errorMessage\":null,\"timeout\":\"60\","
            + "\"smCLI\":null,\"transactionId\":\"tx-1\"}}",
        MediaType.APPLICATION_JSON);
  }

  private static ResponseCreator validated() {
    return withSuccess(
        "{\"responseCode\":200,\"message\":\"SUCCESS\",\"data\":{\"verficationId\":\"vid\","
            + "\"mobileNumber\":\""
            + PHONE
            + "\",\"responseCode\":\"200\",\"errorMessage\":null,"
            + "\"verificationStatus\":\"VERIFICATION_COMPLETED\",\"authToken\":null,"
            + "\"transactionId\":\"tx-1\"}}",
        MediaType.APPLICATION_JSON);
  }

  private static ResponseCreator failure(HttpStatus status, int responseCode, String message) {
    return withStatus(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"responseCode\":" + responseCode + ",\"message\":\"" + message + "\",\"data\":null}");
  }

  private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
