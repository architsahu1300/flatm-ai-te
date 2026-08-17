package com.flatmaite.notification;

import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationRepository notifications;

  public record NotificationResponse(
      UUID id, String type, String title, String body, String data, Instant readAt, Instant createdAt) {

    static NotificationResponse from(Notification n) {
      return new NotificationResponse(
          n.getId(), n.getType().name(), n.getTitle(), n.getBody(), n.getData(), n.getReadAt(), n.getCreatedAt());
    }
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list() {
    AuthPrincipal user = CurrentUser.require();
    List<Notification> all = notifications.findByUserIdOrderByCreatedAtDesc(user.userId());
    return ResponseEntity.ok(
        Map.of(
            "data",
            Map.of(
                "items", all.stream().limit(50).map(NotificationResponse::from).toList(),
                "unread", notifications.countByUserIdAndReadAtIsNull(user.userId()))));
  }

  @GetMapping("/unread-count")
  public ResponseEntity<Map<String, Object>> unreadCount() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", Map.of("unread", notifications.countByUserIdAndReadAtIsNull(user.userId()))));
  }

  @PostMapping("/{id}/read")
  @Transactional
  public ResponseEntity<Map<String, Object>> markRead(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    Notification n =
        notifications.findById(id).orElseThrow(() -> ApiException.notFound("Notification not found"));
    if (!n.getUserId().equals(user.userId())) {
      throw ApiException.notFound("Notification not found");
    }
    if (n.getReadAt() == null) {
      n.setReadAt(Instant.now());
      notifications.save(n);
    }
    return ResponseEntity.ok(Map.of("data", Map.of("read", true)));
  }

  @PostMapping("/read-all")
  @Transactional
  public ResponseEntity<Map<String, Object>> markAllRead() {
    AuthPrincipal user = CurrentUser.require();
    List<Notification> unread =
        notifications.findByUserIdOrderByCreatedAtDesc(user.userId()).stream()
            .filter(n -> n.getReadAt() == null)
            .toList();
    Instant now = Instant.now();
    unread.forEach(n -> n.setReadAt(now));
    notifications.saveAll(unread);
    return ResponseEntity.ok(Map.of("data", Map.of("read", unread.size())));
  }
}
