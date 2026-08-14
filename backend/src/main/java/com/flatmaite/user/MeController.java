package com.flatmaite.user;

import com.flatmaite.auth.AuthDtos.SessionResponse;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.user.UserDtos.PreferencesRequest;
import com.flatmaite.user.UserDtos.ProfileRequest;
import com.flatmaite.user.UserDtos.UpdateMeRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

  private final UserRepository users;
  private final ProfileRepository profiles;
  private final UserPreferencesRepository preferences;
  private final ProfileService profileService;

  @GetMapping
  public ResponseEntity<Map<String, Object>> me() {
    AuthPrincipal principal = CurrentUser.require();
    User user =
        users
            .findById(principal.userId())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> ApiException.unauthorized("Account not found"));
    boolean onboarded =
        profiles.findByUserId(user.getId()).map(p -> p.getProfileCompleteness() > 0).orElse(false);
    return ResponseEntity.ok(Map.of("data", SessionResponse.of(user, onboarded)));
  }

  @PatchMapping
  public ResponseEntity<Map<String, Object>> updateMe(@Valid @RequestBody UpdateMeRequest body) {
    AuthPrincipal principal = CurrentUser.require();
    User user =
        users.findById(principal.userId()).orElseThrow(() -> ApiException.unauthorized("Gone"));
    if (body.name() != null) {
      user.setName(body.name().trim());
    }
    if (body.image() != null) {
      user.setImage(body.image());
    }
    users.save(user);
    return ResponseEntity.ok(Map.of("data", SessionResponse.of(user, true)));
  }

  @DeleteMapping
  public ResponseEntity<Map<String, Object>> deleteMe() {
    AuthPrincipal principal = CurrentUser.require();
    User user =
        users.findById(principal.userId()).orElseThrow(() -> ApiException.unauthorized("Gone"));
    user.setDeletedAt(Instant.now());
    users.save(user);
    return ResponseEntity.ok(Map.of("data", Map.of("deleted", true)));
  }

  @GetMapping("/profile")
  public ResponseEntity<Map<String, Object>> profile() {
    AuthPrincipal principal = CurrentUser.require();
    Profile profile =
        profiles
            .findByUserId(principal.userId())
            .orElseGet(() -> Profile.builder().userId(principal.userId()).build());
    return ResponseEntity.ok(Map.of("data", profile));
  }

  @PutMapping("/profile")
  public ResponseEntity<Map<String, Object>> updateProfile(@Valid @RequestBody ProfileRequest body) {
    AuthPrincipal principal = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", profileService.updateProfile(principal.userId(), body)));
  }

  @GetMapping("/preferences")
  public ResponseEntity<Map<String, Object>> preferences() {
    AuthPrincipal principal = CurrentUser.require();
    UserPreferences prefs =
        preferences
            .findByUserId(principal.userId())
            .orElseGet(() -> UserPreferences.builder().userId(principal.userId()).build());
    return ResponseEntity.ok(Map.of("data", prefs));
  }

  @PutMapping("/preferences")
  public ResponseEntity<Map<String, Object>> updatePreferences(
      @Valid @RequestBody PreferencesRequest body) {
    AuthPrincipal principal = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", profileService.updatePreferences(principal.userId(), body)));
  }
}
