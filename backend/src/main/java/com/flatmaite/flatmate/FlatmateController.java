package com.flatmaite.flatmate;

import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.flatmate.FlatmateDtos.UpsertFlatmateProfileRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FlatmateController {

  private final FlatmateService flatmateService;
  private final FlatmateProfileRepository flatmateProfiles;

  @GetMapping("/flatmates")
  public ResponseEntity<Map<String, Object>> browse(
      @RequestParam(required = false) UUID localityId,
      @RequestParam(required = false) Integer budgetMax,
      @RequestParam(required = false) Boolean hasFlat,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    AuthPrincipal viewer = CurrentUser.orNull();
    return ResponseEntity.ok(
        Map.of(
            "data",
            flatmateService.browse(
                viewer == null ? null : viewer.userId(),
                localityId,
                budgetMax,
                hasFlat,
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 50))));
  }

  @GetMapping("/flatmates/{id}")
  public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
    AuthPrincipal viewer = CurrentUser.orNull();
    return ResponseEntity.ok(
        Map.of("data", flatmateService.detail(id, viewer == null ? null : viewer.userId())));
  }

  @GetMapping("/me/flatmate-profile")
  public ResponseEntity<Map<String, Object>> own() {
    AuthPrincipal user = CurrentUser.require();
    FlatmateProfile fp = flatmateProfiles.findByUserId(user.userId()).orElse(null);
    return ResponseEntity.ok(
        fp == null ? Map.of("data", Map.of("exists", false)) : Map.of("data", fp));
  }

  @PutMapping("/me/flatmate-profile")
  public ResponseEntity<Map<String, Object>> upsert(
      @Valid @RequestBody UpsertFlatmateProfileRequest body) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", flatmateService.upsertOwn(user.userId(), body)));
  }

  @PostMapping("/me/flatmate-profile/activate")
  public ResponseEntity<Map<String, Object>> activate() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", flatmateService.setActive(user.userId(), true)));
  }

  @PostMapping("/me/flatmate-profile/deactivate")
  public ResponseEntity<Map<String, Object>> deactivate() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", flatmateService.setActive(user.userId(), false)));
  }
}
