package com.flatmaite.search;

import com.flatmaite.ai.AiSearchSession;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.ListingDtos;
import com.flatmaite.search.SearchDtos.AiResult;
import com.flatmaite.search.SearchDtos.AiSearchRequest;
import com.flatmaite.search.SearchDtos.AiSearchResponse;
import com.flatmaite.search.SearchDtos.CompareRequest;
import com.flatmaite.search.SearchDtos.CompareResponse;
import com.flatmaite.search.SearchDtos.CompareRow;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiSearchController {

  private static final String ANON_COOKIE = "fm_anon";

  private final SearchPipeline pipeline;
  private final SearchSessionService sessions;
  private final AiUsageService usage;
  private final RateLimiter rateLimiter;
  private final NewQueryDetector newQueryDetector;

  @PostMapping("/search")
  public ResponseEntity<Map<String, Object>> search(
      @Valid @RequestBody AiSearchRequest body, HttpServletRequest req, HttpServletResponse res) {
    AuthPrincipal viewer = CurrentUser.orNull();
    UUID userId = viewer == null ? null : viewer.userId();
    String anonKey = anonKey(req, res, userId);

    rateLimit(userId, anonKey);
    usage.checkQuota(userId, anonKey);

    SearchIntent prior = null;
    AiSearchSession session = null;
    if (body.sessionId() != null) {
      session = sessions.requireOwned(body.sessionId(), userId, anonKey);
      prior = sessions.intentOf(session);
    }

    // A complete new request must not inherit the previous search's constraints — otherwise a
    // stale locality or room type silently zeroes out results the user can plainly see exist.
    String note = null;
    if (prior != null && newQueryDetector.isSelfContained(body.query())) {
      prior = null;
      note = "Started a fresh search — this read as a new request, not a tweak of the last one.";
    }

    SearchIntent intent = pipeline.extractIntent(body.query(), prior, userId, anonKey);
    if (session == null) {
      session = sessions.start(userId, anonKey, intent, body.query());
    }

    AiSearchResponse result = pipeline.search(intent, userId, anonKey, session.getId(), note);
    List<UUID> resultIds = new ArrayList<>();
    result.homes().forEach(r -> resultIds.add(r.home().id()));
    result.flatmates().forEach(r -> resultIds.add(r.flatmate().id()));
    sessions.update(session, intent, body.query(), resultIds);

    return ResponseEntity.ok(Map.of("data", result));
  }

  /** Refinement is the same handler family — requires an existing session. */
  @PostMapping("/refine")
  public ResponseEntity<Map<String, Object>> refine(
      @Valid @RequestBody AiSearchRequest body, HttpServletRequest req, HttpServletResponse res) {
    if (body.sessionId() == null) {
      throw ApiException.badRequest("session_required", "Refinement needs an active search session");
    }
    return search(body, req, res);
  }

  public record ApplyIntentRequest(UUID sessionId, SearchIntent intent) {}

  /**
   * Chip edits and relaxer clicks: the UI sends the FULL modified intent — no LLM involved. The
   * intent is replayed through the deterministic pipeline and becomes the session's current state.
   */
  @PostMapping("/apply")
  public ResponseEntity<Map<String, Object>> apply(
      @Valid @RequestBody ApplyIntentRequest body, HttpServletRequest req, HttpServletResponse res) {
    if (body.sessionId() == null || body.intent() == null) {
      throw ApiException.badRequest("invalid_request", "sessionId and intent are required");
    }
    AuthPrincipal viewer = CurrentUser.orNull();
    UUID userId = viewer == null ? null : viewer.userId();
    String anonKey = anonKey(req, res, userId);
    rateLimit(userId, anonKey);

    AiSearchSession session = sessions.requireOwned(body.sessionId(), userId, anonKey);
    AiSearchResponse result = pipeline.search(body.intent(), userId, anonKey, session.getId());
    List<UUID> resultIds = new ArrayList<>();
    result.homes().forEach(r -> resultIds.add(r.home().id()));
    result.flatmates().forEach(r -> resultIds.add(r.flatmate().id()));
    sessions.update(session, body.intent(), "(edited requirements)", resultIds);
    return ResponseEntity.ok(Map.of("data", result));
  }

  @PostMapping("/compare")
  public ResponseEntity<Map<String, Object>> compare(
      @Valid @RequestBody CompareRequest body, HttpServletRequest req, HttpServletResponse res) {
    AuthPrincipal viewer = CurrentUser.orNull();
    UUID userId = viewer == null ? null : viewer.userId();
    String anonKey = anonKey(req, res, userId);

    AiSearchSession session = sessions.requireOwned(body.sessionId(), userId, anonKey);
    SearchIntent intent = sessions.intentOf(session);
    List<UUID> allowed = List.of(session.getLastResultIds());
    List<UUID> wanted = body.candidateIds().stream().filter(allowed::contains).toList();
    if (wanted.size() < 2) {
      throw ApiException.badRequest("compare_needs_results", "Pick 2–3 results from this search to compare");
    }

    AiSearchResponse fresh = pipeline.search(intent, userId, anonKey, session.getId());
    List<AiResult> items =
        fresh.homes().stream().filter(r -> wanted.contains(r.home().id())).toList();
    if (items.size() < 2) {
      throw ApiException.badRequest("compare_needs_results", "Those results are no longer available");
    }
    return ResponseEntity.ok(Map.of("data", buildComparison(items)));
  }

  private static CompareResponse buildComparison(List<AiResult> items) {
    List<CompareRow> rows = new ArrayList<>();
    rows.add(row("Match", items, r -> r.matchScore() + "%", true, AiResult::matchScore));
    rows.add(row("Rent", items, r -> "₹%,d/mo".formatted(r.home().rentMonthly()), false,
        r -> -r.home().rentMonthly()));
    rows.add(row("Deposit", items, r -> "₹%,d".formatted(r.home().deposit()), false, r -> -r.home().deposit()));
    rows.add(row("Room", items, r -> pretty(r.home().roomType().name()), true, r -> 0));
    rows.add(row("Furnishing", items, r -> pretty(r.home().furnishing().name()), true, r -> 0));
    rows.add(
        row("Commute", items, r -> r.commuteMinutes() == null ? "—" : "~" + r.commuteMinutes() + " min", false,
            r -> r.commuteMinutes() == null ? Integer.MIN_VALUE : -r.commuteMinutes()));
    rows.add(row("Available", items, r -> String.valueOf(r.home().availableFrom()), true, r -> 0));
    rows.add(
        row("Top concern", items, r -> r.concerns().isEmpty() ? "None flagged" : r.concerns().get(0), true, r -> 0));

    AiResult best = items.stream().max((a, b) -> Integer.compare(a.matchScore(), b.matchScore())).orElseThrow();
    String summary =
        "\"%s\" scores highest (%d%%) for your search — mainly on %s. Cheapest is ₹%,d/mo."
            .formatted(
                best.home().title(),
                best.matchScore(),
                best.matchReasons().isEmpty() ? "overall fit" : best.matchReasons().get(0).toLowerCase(),
                items.stream().mapToInt(r -> r.home().rentMonthly()).min().orElse(0));
    return new CompareResponse(items, rows, summary);
  }

  private static CompareRow row(
      String label,
      List<AiResult> items,
      java.util.function.Function<AiResult, String> render,
      boolean noBest,
      java.util.function.ToIntFunction<AiResult> rank) {
    List<String> values = items.stream().map(render).toList();
    Integer bestIndex = null;
    if (!noBest) {
      int best = 0;
      for (int i = 1; i < items.size(); i++) {
        if (rank.applyAsInt(items.get(i)) > rank.applyAsInt(items.get(best))) {
          best = i;
        }
      }
      bestIndex = best;
    }
    return new CompareRow(label, values, bestIndex);
  }

  private static String pretty(String enumName) {
    return enumName.charAt(0) + enumName.substring(1).toLowerCase().replace('_', ' ');
  }

  private void rateLimit(UUID userId, String anonKey) {
    String bucket = "ai:" + (userId != null ? userId : anonKey);
    if (!rateLimiter.tryAcquire(bucket, 10, 6)) { // 10 burst, ~10/min sustained
      throw ApiException.tooManyRequests("Slow down a little — try again in a few seconds");
    }
  }

  /** Stable anon key for guests: cookie + fallback to IP. Sets the cookie when absent. */
  private String anonKey(HttpServletRequest req, HttpServletResponse res, UUID userId) {
    if (userId != null) {
      return null;
    }
    if (req.getCookies() != null) {
      for (Cookie c : req.getCookies()) {
        if (ANON_COOKIE.equals(c.getName()) && c.getValue().length() >= 16) {
          return c.getValue();
        }
      }
    }
    String key = UUID.randomUUID().toString().replace("-", "");
    Cookie cookie = new Cookie(ANON_COOKIE, key);
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(60 * 60 * 24 * 30);
    cookie.setAttribute("SameSite", "Lax");
    res.addCookie(cookie);
    return key;
  }

}
