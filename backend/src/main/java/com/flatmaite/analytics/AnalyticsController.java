package com.flatmaite.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Append-only product analytics. Whitelisted event names; anything else is dropped, not errored. */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

  private static final Set<String> ALLOWED_EVENTS =
      Set.of(
          "ai_search",
          "search_refined",
          "result_clicked",
          "listing_viewed",
          "flatmate_viewed",
          "listing_saved",
          "search_saved",
          "contact_initiated",
          "message_sent",
          "agreement_started",
          "boost_purchased",
          "plan_viewed",
          "seo_page_viewed");

  private final AnalyticsEventRepository events;
  private final RateLimiter rateLimiter;
  private final ObjectMapper objectMapper;

  public record TrackRequest(
      @NotBlank @Size(max = 80) String event,
      Map<String, Object> properties,
      @Size(max = 64) String sessionId) {}

  @SneakyThrows
  @PostMapping("/events")
  public ResponseEntity<Map<String, Object>> track(
      @Valid @RequestBody TrackRequest body, HttpServletRequest request) {
    AuthPrincipal user = CurrentUser.orNull();
    String anonKey = user != null ? user.userId().toString() : request.getRemoteAddr();
    // silently accept-and-drop over the cap: analytics must never break the product
    if (!rateLimiter.tryAcquire("analytics:" + anonKey, 60, 60)
        || !ALLOWED_EVENTS.contains(body.event())) {
      return ResponseEntity.ok(Map.of("data", Map.of("tracked", false)));
    }
    events.save(
        AnalyticsEvent.builder()
            .userId(user != null ? user.userId() : null)
            .anonymousId(user == null ? anonKey : null)
            .event(body.event())
            .properties(objectMapper.writeValueAsString(body.properties() == null ? Map.of() : body.properties()))
            .sessionId(body.sessionId())
            .build());
    return ResponseEntity.ok(Map.of("data", Map.of("tracked", true)));
  }
}
