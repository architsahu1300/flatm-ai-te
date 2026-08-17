package com.flatmaite.admin;

import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ReportStatus;
import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.ScamScoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Role gate: /api/v1/admin/** requires ROLE_ADMIN at the security-filter level. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

  private final AdminService admin;
  private final ScamScoreService scamScore;

  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats() {
    return ResponseEntity.ok(Map.of("data", admin.stats()));
  }

  // ---------- users ----------

  @GetMapping("/users")
  public ResponseEntity<Map<String, Object>> users(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return ResponseEntity.ok(Map.of("data", admin.users(q, page, Math.min(size, 100))));
  }

  public record SuspendRequest(boolean suspended) {}

  @PatchMapping("/users/{id}")
  public ResponseEntity<Map<String, Object>> suspend(
      @PathVariable UUID id, @RequestBody SuspendRequest body) {
    if (id.equals(CurrentUser.require().userId())) {
      throw ApiException.badRequest("self_suspend", "You can't suspend yourself");
    }
    admin.setSuspended(id, body.suspended());
    return ResponseEntity.ok(Map.of("data", Map.of("id", id, "suspended", body.suspended())));
  }

  // ---------- listings ----------

  @GetMapping("/listings")
  public ResponseEntity<Map<String, Object>> listings(
      @RequestParam(required = false) ListingStatus status,
      @RequestParam(defaultValue = "false") boolean suspicious,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return ResponseEntity.ok(
        Map.of("data", admin.listings(status, suspicious, page, Math.min(size, 100))));
  }

  @PatchMapping("/listings/{id}")
  public ResponseEntity<Map<String, Object>> removeListing(@PathVariable UUID id) {
    admin.removeListing(id);
    return ResponseEntity.ok(Map.of("data", Map.of("id", id, "status", "REMOVED")));
  }

  @PostMapping("/listings/rescore")
  public ResponseEntity<Map<String, Object>> rescore() {
    return ResponseEntity.ok(Map.of("data", Map.of("recomputed", scamScore.recomputeAll())));
  }

  // ---------- reports ----------

  @GetMapping("/reports")
  public ResponseEntity<Map<String, Object>> reports(
      @RequestParam(required = false) ReportStatus status) {
    return ResponseEntity.ok(Map.of("data", admin.reports(status)));
  }

  public record ResolveReportRequest(ReportStatus status, @Size(max = 1000) String resolutionNote) {}

  @PatchMapping("/reports/{id}")
  public ResponseEntity<Map<String, Object>> resolveReport(
      @PathVariable UUID id, @Valid @RequestBody ResolveReportRequest body) {
    admin.resolveReport(CurrentUser.require().userId(), id, body.status(), body.resolutionNote());
    return ResponseEntity.ok(Map.of("data", Map.of("id", id, "status", body.status().name())));
  }

  // ---------- verifications ----------

  @GetMapping("/verifications")
  public ResponseEntity<Map<String, Object>> verifications(
      @RequestParam(defaultValue = "PENDING") VerificationStatus status) {
    return ResponseEntity.ok(Map.of("data", admin.verifications(status)));
  }

  public record ReviewVerificationRequest(boolean approve) {}

  @PatchMapping("/verifications/{id}")
  public ResponseEntity<Map<String, Object>> reviewVerification(
      @PathVariable UUID id, @RequestBody ReviewVerificationRequest body) {
    admin.reviewVerification(CurrentUser.require().userId(), id, body.approve());
    return ResponseEntity.ok(
        Map.of("data", Map.of("id", id, "status", body.approve() ? "VERIFIED" : "REJECTED")));
  }

  // ---------- AI usage ----------

  @GetMapping("/ai-usage")
  public ResponseEntity<Map<String, Object>> aiUsage() {
    return ResponseEntity.ok(Map.of("data", admin.aiUsage()));
  }
}
