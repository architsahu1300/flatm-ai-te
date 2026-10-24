package com.flatmaite.auth.otp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.web.ApiException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Message Central Verify Now. They generate, send and check the code from their own pre-approved
 * OTP template, which is what lets us text Indian numbers without a DLT registration of our own.
 *
 * <p>Their send call returns a {@code verificationId} that the check call needs back. It is kept
 * here per phone, in memory, so a restart between the two means "resend" — the same trade the mock
 * makes.
 *
 * <p>Their docs leave gaps (the token's lifetime, the HTTP status that carries an error, what 506
 * is for). So this reads their error code from the body whatever the HTTP status, takes the
 * token's lifetime from its own {@code exp} claim, and treats any reply it does not recognise as an
 * outage rather than a wrong code.
 */
@Slf4j
public class MessageCentralOtpProvider implements OtpProvider {

  public static final String NAME = "message-central";

  static final String COUNTRY_CODE = "91";

  /** Matches {@code OtpVerifyRequest}'s six-digit pattern and the six-digit box in the UI. */
  static final int OTP_LENGTH = 6;

  private static final int SUCCESS = 200;
  private static final int REQUEST_ALREADY_EXISTS = 506;
  private static final int WRONG_OTP_PROVIDED = 702;

  /** Invalid id, failed, already verified, expired, too many attempts: this id will never verify. */
  private static final Set<Integer> CODE_SPENT = Set.of(505, 700, 703, 705, 800);

  private static final String VERIFICATION_COMPLETED = "VERIFICATION_COMPLETED";

  /** Refresh this long before the token's own expiry, so no call leaves with a dying token. */
  private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(1);

  /** Used when a token's expiry cannot be read from it. */
  private static final Duration TOKEN_FALLBACK_LIFETIME = Duration.ofMinutes(30);

  private static final Duration VERIFICATION_ID_TTL = Duration.ofMinutes(10);

  private static final String UNAVAILABLE =
      "Couldn't reach the SMS service — try again in a minute, or sign in with email.";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestClient http;
  private final String customerId;
  private final String key;
  private final Clock clock;
  private final Cache<String, String> verificationIds =
      Caffeine.newBuilder().expireAfterWrite(VERIFICATION_ID_TTL).maximumSize(50_000).build();

  private Token token; // guarded by this

  public MessageCentralOtpProvider(
      RestClient.Builder http, FlatmaiteProperties.MessageCentral config) {
    this(http, config, Clock.systemUTC());
  }

  MessageCentralOtpProvider(
      RestClient.Builder http, FlatmaiteProperties.MessageCentral config, Clock clock) {
    this.http = http.baseUrl(config.getBaseUrl()).build();
    this.customerId = config.getCustomerId();
    this.key =
        Base64.getEncoder().encodeToString(config.getPassword().getBytes(StandardCharsets.UTF_8));
    this.clock = clock;
  }

  @Override
  public void send(String phone) {
    Reply reply =
        withToken(
            "send",
            auth ->
                http.post()
                    .uri(
                        uri(
                            "/verification/v3/send",
                            Map.of(
                                "countryCode", COUNTRY_CODE,
                                "customerId", customerId,
                                "flowType", "SMS",
                                "mobileNumber", phone,
                                "otpLength", OTP_LENGTH)))
                    .header("authToken", auth));
    String verificationId = reply.data("verificationId");
    if (reply.code() == SUCCESS && verificationId != null) {
      verificationIds.put(phone, verificationId);
      return;
    }
    if (reply.code() == REQUEST_ALREADY_EXISTS) {
      throw ApiException.tooManyRequests(
          "A code was just sent to this number — wait a minute before asking again");
    }
    throw outage("send", reply);
  }

  @Override
  public boolean verify(String phone, String code) {
    String verificationId = verificationIds.getIfPresent(phone);
    if (verificationId == null) {
      return false;
    }
    Reply reply =
        withToken(
            "validate",
            auth ->
                http.get()
                    .uri(
                        uri(
                            "/verification/v3/validateOtp",
                            Map.of("verificationId", verificationId, "code", code)))
                    .header("authToken", auth));
    if (reply.code() == SUCCESS) {
      // fail closed: a success code without a completed status is not a sign-in
      boolean completed = VERIFICATION_COMPLETED.equals(reply.data("verificationStatus"));
      if (completed) {
        verificationIds.asMap().remove(phone, verificationId);
      }
      return completed;
    }
    if (reply.code() == WRONG_OTP_PROVIDED) {
      return false;
    }
    if (CODE_SPENT.contains(reply.code())) {
      // conditional: a resend that landed meanwhile keeps its own id
      verificationIds.asMap().remove(phone, verificationId);
      return false;
    }
    throw outage("validate", reply);
  }

  @Override
  public String name() {
    return NAME;
  }

  /** Runs a call with a live token; a rejected token is replaced once and the call retried. */
  private Reply withToken(String operation, Function<String, RestClient.RequestHeadersSpec<?>> call) {
    String auth = token();
    Reply reply = execute(operation, call.apply(auth));
    if (!reply.authRejected()) {
      return reply;
    }
    discardToken(auth);
    reply = execute(operation, call.apply(token()));
    if (reply.authRejected()) {
      throw outage(operation, reply);
    }
    return reply;
  }

  private synchronized String token() {
    Instant now = clock.instant();
    if (token == null || !now.isBefore(token.expiresAt().minus(TOKEN_REFRESH_MARGIN))) {
      token = fetchToken(now);
    }
    return token.value();
  }

  private synchronized void discardToken(String rejected) {
    if (token != null && token.value().equals(rejected)) {
      token = null;
    }
  }

  private Token fetchToken(Instant now) {
    Reply reply =
        execute(
            "token",
            http.get()
                .uri(
                    uri(
                        "/auth/v1/authentication/token",
                        Map.of(
                            "customerId", customerId,
                            "key", key,
                            "scope", "NEW",
                            "country", COUNTRY_CODE))));
    String value = reply.body().path("token").textValue();
    if (reply.httpStatus() != SUCCESS || value == null || value.isBlank()) {
      throw outage("token", reply);
    }
    return new Token(value, expiryOf(value, now));
  }

  /** Their token is a JWT, and its {@code exp} claim is the only lifetime they publish. */
  static Instant expiryOf(String jwt, Instant now) {
    try {
      String[] parts = jwt.split("\\.");
      JsonNode exp = JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("exp");
      if (exp.canConvertToLong()) {
        return Instant.ofEpochSecond(exp.asLong());
      }
    } catch (IOException | RuntimeException e) {
      // not a readable JWT: fall back to a short lifetime, and the 401 retry covers the rest
    }
    return now.plus(TOKEN_FALLBACK_LIFETIME);
  }

  private static Reply execute(String operation, RestClient.RequestHeadersSpec<?> request) {
    try {
      return request.exchange((req, res) -> Reply.read(res.getStatusCode().value(), res.getBody()));
    } catch (RestClientException e) {
      log.warn("Message Central {} unreachable: {}", operation, e.getMessage());
      throw ApiException.serviceUnavailable(UNAVAILABLE);
    }
  }

  /** Every value goes in as a URI variable, so it is fully encoded: a '+' reaches them as %2B. */
  private static Function<UriBuilder, URI> uri(String path, Map<String, ?> params) {
    return builder -> {
      builder.path(path);
      params.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
      return builder.build(params);
    };
  }

  private static ApiException outage(String operation, Reply reply) {
    log.warn(
        "Message Central {} failed: HTTP {}, responseCode {}, message '{}'",
        operation,
        reply.httpStatus(),
        reply.code(),
        reply.message());
    return ApiException.serviceUnavailable(UNAVAILABLE);
  }

  private record Token(String value, Instant expiresAt) {}

  private record Reply(int httpStatus, JsonNode body) {

    static Reply read(int httpStatus, InputStream stream) throws IOException {
      byte[] bytes = stream.readAllBytes();
      JsonNode body = MissingNode.getInstance();
      if (bytes.length > 0) {
        try {
          body = JSON.readTree(bytes);
        } catch (JsonProcessingException e) {
          // not JSON (a proxy's error page, say): judged by the HTTP status alone
        }
      }
      return new Reply(httpStatus, body);
    }

    /** Their own code from the body when there is one, else the HTTP status. */
    int code() {
      return body.path("responseCode").asInt(httpStatus);
    }

    boolean authRejected() {
      return httpStatus == 401 || httpStatus == 403 || code() == 401 || code() == 403;
    }

    String data(String field) {
      return body.path("data").path(field).textValue();
    }

    String message() {
      return body.path("message").asText("");
    }
  }
}
