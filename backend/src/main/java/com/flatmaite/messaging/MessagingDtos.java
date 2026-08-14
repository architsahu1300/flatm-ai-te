package com.flatmaite.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class MessagingDtos {

  private MessagingDtos() {}

  public record StartConversationRequest(
      @NotNull UUID recipientId, UUID listingId, @NotBlank @Size(max = 2000) String firstMessage) {}

  public record SendMessageRequest(@NotBlank @Size(max = 2000) String body) {}

  public record ConversationResponse(
      UUID id,
      UUID otherUserId,
      String otherUserName,
      String otherUserImage,
      String status,
      boolean isInitiator,
      UUID listingId,
      String listingTitle,
      Integer listingRent,
      String lastPreview,
      Instant lastMessageAt,
      long unreadCount) {}

  public record MessageResponse(UUID id, UUID senderId, String body, Instant readAt, Instant createdAt) {}
}
