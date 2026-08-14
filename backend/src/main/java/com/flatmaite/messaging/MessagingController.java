package com.flatmaite.messaging;

import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.messaging.MessagingDtos.SendMessageRequest;
import com.flatmaite.messaging.MessagingDtos.StartConversationRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class MessagingController {

  private final MessagingService messaging;

  @GetMapping
  public ResponseEntity<Map<String, Object>> list() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", messaging.list(user.userId())));
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartConversationRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Conversation conversation =
        messaging.start(user.userId(), body.recipientId(), body.listingId(), body.firstMessage());
    return ResponseEntity.ok(
        Map.of("data", Map.of("id", conversation.getId(), "status", conversation.getStatus().name())));
  }

  @GetMapping("/{id}/messages")
  public ResponseEntity<Map<String, Object>> messages(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant after) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", messaging.messagesOf(user.userId(), id, after)));
  }

  @PostMapping("/{id}/messages")
  public ResponseEntity<Map<String, Object>> send(
      @PathVariable UUID id, @Valid @RequestBody SendMessageRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Message message = messaging.send(user.userId(), id, body.body());
    return ResponseEntity.ok(
        Map.of(
            "data",
            new MessagingDtos.MessageResponse(
                message.getId(), message.getSenderId(), message.getBody(), message.getReadAt(), message.getCreatedAt())));
  }

  @PostMapping("/{id}/accept")
  public ResponseEntity<Map<String, Object>> accept(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", Map.of("status", messaging.respond(user.userId(), id, true).getStatus().name())));
  }

  @PostMapping("/{id}/reject")
  public ResponseEntity<Map<String, Object>> reject(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", Map.of("status", messaging.respond(user.userId(), id, false).getStatus().name())));
  }

  @PostMapping("/{id}/block")
  public ResponseEntity<Map<String, Object>> block(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", Map.of("status", messaging.block(user.userId(), id).getStatus().name())));
  }

  @PostMapping("/{id}/read")
  public ResponseEntity<Map<String, Object>> read(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    messaging.markRead(user.userId(), id);
    return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
  }
}
