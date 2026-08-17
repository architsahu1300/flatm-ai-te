package com.flatmaite.report;

import com.flatmaite.common.domain.ReportReason;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.ScamScoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

  public record CreateReportRequest(
      UUID reportedUserId,
      UUID reportedListingId,
      @NotNull ReportReason reason,
      @Size(max = 2000) String details) {}

  private final ReportRepository reports;
  private final RateLimiter rateLimiter;
  private final ScamScoreService scamScore;

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateReportRequest body) {
    AuthPrincipal user = CurrentUser.require();
    if (body.reportedUserId() == null && body.reportedListingId() == null) {
      throw ApiException.badRequest("target_required", "Report a listing or a member");
    }
    if (user.userId().equals(body.reportedUserId())) {
      throw ApiException.badRequest("self_report", "You can't report yourself");
    }
    if (!rateLimiter.tryAcquire("reports:" + user.userId(), 5, 17280)) { // 5/day
      throw ApiException.tooManyRequests("You've filed several reports today — our team is on it");
    }
    Report report =
        reports.save(
            Report.builder()
                .reporterId(user.userId())
                .reportedUserId(body.reportedUserId())
                .reportedListingId(body.reportedListingId())
                .reason(body.reason())
                .details(body.details())
                .build());
    // scam/fake reports immediately feed the listing's advisory risk score
    if (body.reportedListingId() != null
        && (body.reason() == ReportReason.SCAM || body.reason() == ReportReason.FAKE_LISTING)) {
      scamScore.recompute(body.reportedListingId());
    }
    return ResponseEntity.ok(
        Map.of("data", Map.of("id", report.getId(), "status", report.getStatus().name())));
  }
}
