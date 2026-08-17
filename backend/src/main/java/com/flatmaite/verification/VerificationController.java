package com.flatmaite.verification;

import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.domain.VerificationType;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service verification requests. Phone/email auto-verify via the mock provider (they're
 * already OTP/link-verified at auth level); GOV_ID, SELFIE and PROPERTY go PENDING into the admin
 * review queue. Badges only ever show for rows an actual process marked VERIFIED.
 */
@RestController
@RequestMapping("/api/v1/me/verifications")
@RequiredArgsConstructor
public class VerificationController {

  public record RequestVerification(@NotNull VerificationType type) {}

  private final VerificationRepository verifications;
  private final UserRepository users;

  @GetMapping
  public ResponseEntity<Map<String, Object>> mine() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", verifications.findByUserId(user.userId())));
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> request(@Valid @RequestBody RequestVerification body) {
    AuthPrincipal principal = CurrentUser.require();
    boolean exists =
        verifications.findByUserId(principal.userId()).stream()
            .anyMatch(v -> v.getType() == body.type()
                && (v.getStatus() == VerificationStatus.VERIFIED || v.getStatus() == VerificationStatus.PENDING));
    if (exists) {
      throw ApiException.conflict("already_requested", "Already verified or pending review");
    }

    boolean autoVerify = body.type() == VerificationType.PHONE || body.type() == VerificationType.EMAIL;
    Verification verification =
        verifications.save(
            Verification.builder()
                .userId(principal.userId())
                .type(body.type())
                .status(autoVerify ? VerificationStatus.VERIFIED : VerificationStatus.PENDING)
                .provider("mock")
                .build());
    if (autoVerify) {
      User user = users.findById(principal.userId()).orElseThrow();
      if (body.type() == VerificationType.EMAIL && user.getEmailVerifiedAt() == null) {
        user.setEmailVerifiedAt(Instant.now());
      }
      if (body.type() == VerificationType.PHONE && user.getPhoneVerifiedAt() == null) {
        user.setPhoneVerifiedAt(Instant.now());
      }
      users.save(user);
    }
    return ResponseEntity.ok(Map.of("data", verification));
  }
}
