package com.flatmaite.auth;

import com.flatmaite.auth.AuthDtos.LoginRequest;
import com.flatmaite.auth.AuthDtos.OtpRequest;
import com.flatmaite.auth.AuthDtos.OtpVerifyRequest;
import com.flatmaite.auth.AuthDtos.RegisterRequest;
import com.flatmaite.auth.AuthDtos.SessionResponse;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.security.JwtService;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final OtpService otpService;
  private final JwtService jwtService;
  private final RateLimiter rateLimiter;
  private final UserRepository users;
  private final FlatmaiteProperties props;

  @PostMapping("/register")
  public ResponseEntity<Map<String, Object>> register(
      @Valid @RequestBody RegisterRequest body, HttpServletRequest req, HttpServletResponse res) {
    // 5 registrations/hour/IP
    if (!rateLimiter.tryAcquire("auth:register:" + clientIp(req), 5, 720)) {
      throw ApiException.tooManyRequests("Too many signups from this address — try again later");
    }
    User user = authService.register(body.name(), body.email(), body.password());
    issueSession(user, res);
    return ResponseEntity.ok(Map.of("data", SessionResponse.of(user, false)));
  }

  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(
      @Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
    // 10 attempts / 10 min / IP
    if (!rateLimiter.tryAcquire("auth:login:" + clientIp(req), 10, 60)) {
      throw ApiException.tooManyRequests("Too many login attempts — try again later");
    }
    User user = authService.login(body.email(), body.password());
    issueSession(user, res);
    return ResponseEntity.ok(
        Map.of("data", SessionResponse.of(user, authService.isOnboarded(user.getId()))));
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String, Object>> logout(HttpServletResponse res) {
    jwtService.clearCookie(res);
    return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
  }

  @PostMapping("/otp/request")
  public ResponseEntity<Map<String, Object>> otpRequest(
      @Valid @RequestBody OtpRequest body, HttpServletRequest req) {
    // 3 codes / 10 min / phone
    if (!rateLimiter.tryAcquire("auth:otp:" + body.phone(), 3, 200)) {
      throw ApiException.tooManyRequests("Too many OTP requests for this number");
    }
    otpService.request(body.phone());
    return ResponseEntity.ok(
        Map.of("data", Map.of("sent", true, "note", "Mock provider — code is in the backend log")));
  }

  @PostMapping("/otp/verify")
  public ResponseEntity<Map<String, Object>> otpVerify(
      @Valid @RequestBody OtpVerifyRequest body, HttpServletResponse res) {
    if (!otpService.verify(body.phone(), body.otp())) {
      throw ApiException.unauthorized("Invalid or expired code");
    }
    User user = authService.loginByPhone(body.phone(), body.name());
    issueSession(user, res);
    return ResponseEntity.ok(
        Map.of("data", SessionResponse.of(user, authService.isOnboarded(user.getId()))));
  }

  @GetMapping("/session")
  public ResponseEntity<Map<String, Object>> session() {
    AuthPrincipal principal = CurrentUser.orNull();
    if (principal == null) {
      return ResponseEntity.ok(Map.of("data", Map.of("authenticated", false)));
    }
    User user =
        users.findById(principal.userId()).orElseThrow(() -> ApiException.unauthorized("Gone"));
    return ResponseEntity.ok(
        Map.of(
            "data",
            Map.of(
                "authenticated",
                true,
                "user",
                SessionResponse.of(user, authService.isOnboarded(user.getId())))));
  }

  /** Which auth providers are available (frontend hides Google when unconfigured). */
  @GetMapping("/providers")
  public ResponseEntity<Map<String, Object>> providers() {
    return ResponseEntity.ok(
        Map.of("data", Map.of("google", props.getGoogle().isConfigured(), "otp", true)));
  }

  private void issueSession(User user, HttpServletResponse res) {
    jwtService.writeCookie(res, jwtService.issue(user.getId(), user.getRole(), user.getName()));
  }

  private String clientIp(HttpServletRequest req) {
    String forwarded = req.getHeader("X-Forwarded-For");
    return forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
  }
}
