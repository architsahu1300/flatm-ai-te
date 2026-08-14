package com.flatmaite.messaging;

import com.flatmaite.common.domain.ConversationStatus;
import com.flatmaite.common.domain.NotificationType;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingRepository;
import com.flatmaite.messaging.MessagingDtos.ConversationResponse;
import com.flatmaite.messaging.MessagingDtos.MessageResponse;
import com.flatmaite.notification.Notification;
import com.flatmaite.notification.NotificationRepository;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Message-request model: the first message opens a PENDING conversation; the recipient accepts,
 * rejects, or blocks. Only ACCEPTED conversations flow freely. Phone numbers are never exposed —
 * moving off-platform is a user choice inside the chat.
 */
@Service
@RequiredArgsConstructor
public class MessagingService {

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final UserBlockRepository blocks;
  private final UserRepository users;
  private final ListingRepository listings;
  private final NotificationRepository notifications;
  private final RateLimiter rateLimiter;

  @Transactional
  public Conversation start(UUID initiatorId, UUID recipientId, UUID listingId, String firstMessage) {
    if (initiatorId.equals(recipientId)) {
      throw ApiException.badRequest("self_message", "You can't message yourself");
    }
    ensureNotBlocked(initiatorId, recipientId);
    users
        .findById(recipientId)
        .filter(u -> u.getDeletedAt() == null && !u.isSuspended())
        .orElseThrow(() -> ApiException.notFound("This member is no longer available"));

    // existing conversation (either direction) for this listing context → reuse
    UUID normalizedListing = listingId;
    Conversation existing =
        conversations
            .findByInitiatorIdAndRecipientIdAndListingId(initiatorId, recipientId, normalizedListing)
            .or(() -> conversations.findByInitiatorIdAndRecipientIdAndListingId(recipientId, initiatorId, normalizedListing))
            .orElse(null);
    if (existing != null) {
      if (existing.getStatus() == ConversationStatus.BLOCKED) {
        throw ApiException.forbidden("This conversation is blocked");
      }
      sendInternal(existing, initiatorId, firstMessage);
      return existing;
    }

    if (!rateLimiter.tryAcquire("msg:newconv:" + initiatorId, 10, 8640)) { // 10/day
      throw ApiException.tooManyRequests("You've started a lot of conversations today — try again tomorrow");
    }

    Conversation conversation =
        conversations.save(
            Conversation.builder()
                .initiatorId(initiatorId)
                .recipientId(recipientId)
                .listingId(listingId)
                .status(ConversationStatus.PENDING)
                .lastMessageAt(Instant.now())
                .build());
    messages.save(
        Message.builder().conversationId(conversation.getId()).senderId(initiatorId).body(firstMessage).build());
    notifications.save(
        Notification.builder()
            .userId(recipientId)
            .type(NotificationType.MESSAGE_REQUEST)
            .title("New message request")
            .body(firstMessage.length() > 80 ? firstMessage.substring(0, 77) + "…" : firstMessage)
            .data("{\"conversationId\":\"" + conversation.getId() + "\"}")
            .build());
    return conversation;
  }

  @Transactional
  public Message send(UUID senderId, UUID conversationId, String body) {
    Conversation conversation = participantConversation(senderId, conversationId);
    if (conversation.getStatus() == ConversationStatus.PENDING) {
      throw ApiException.forbidden(
          senderId.equals(conversation.getInitiatorId())
              ? "Wait for them to accept your request before sending more messages"
              : "Accept the request to start chatting");
    }
    if (conversation.getStatus() != ConversationStatus.ACCEPTED) {
      throw ApiException.forbidden("This conversation is closed");
    }
    if (!rateLimiter.tryAcquire("msg:send:" + senderId, 30, 2)) {
      throw ApiException.tooManyRequests("Slow down a little");
    }
    return sendInternal(conversation, senderId, body);
  }

  private Message sendInternal(Conversation conversation, UUID senderId, String body) {
    Message message =
        messages.save(
            Message.builder().conversationId(conversation.getId()).senderId(senderId).body(body).build());
    conversation.setLastMessageAt(Instant.now());
    conversations.save(conversation);
    UUID recipient =
        senderId.equals(conversation.getInitiatorId())
            ? conversation.getRecipientId()
            : conversation.getInitiatorId();
    notifications.save(
        Notification.builder()
            .userId(recipient)
            .type(NotificationType.MESSAGE)
            .title("New message")
            .body(body.length() > 80 ? body.substring(0, 77) + "…" : body)
            .data("{\"conversationId\":\"" + conversation.getId() + "\"}")
            .build());
    return message;
  }

  @Transactional
  public Conversation respond(UUID userId, UUID conversationId, boolean accept) {
    Conversation conversation = participantConversation(userId, conversationId);
    if (!userId.equals(conversation.getRecipientId())) {
      throw ApiException.forbidden("Only the recipient can respond to a request");
    }
    if (conversation.getStatus() != ConversationStatus.PENDING) {
      throw ApiException.badRequest("not_pending", "This request was already handled");
    }
    conversation.setStatus(accept ? ConversationStatus.ACCEPTED : ConversationStatus.REJECTED);
    return conversations.save(conversation);
  }

  @Transactional
  public Conversation block(UUID userId, UUID conversationId) {
    Conversation conversation = participantConversation(userId, conversationId);
    conversation.setStatus(ConversationStatus.BLOCKED);
    conversation.setBlockedBy(userId);
    UUID other =
        userId.equals(conversation.getInitiatorId())
            ? conversation.getRecipientId()
            : conversation.getInitiatorId();
    if (blocks.findById(new UserBlock.Key(userId, other)).isEmpty()) {
      blocks.save(new UserBlock(new UserBlock.Key(userId, other), null));
    }
    return conversations.save(conversation);
  }

  @Transactional
  public void markRead(UUID userId, UUID conversationId) {
    Conversation conversation = participantConversation(userId, conversationId);
    List<Message> unread =
        messages.findByConversationIdAndDeletedAtIsNullOrderByCreatedAtAsc(conversation.getId()).stream()
            .filter(m -> m.getReadAt() == null && !m.getSenderId().equals(userId))
            .toList();
    Instant now = Instant.now();
    unread.forEach(m -> m.setReadAt(now));
    messages.saveAll(unread);
  }

  @Transactional(readOnly = true)
  public List<ConversationResponse> list(UUID userId) {
    List<Conversation> mine =
        conversations.findByInitiatorIdOrRecipientIdOrderByLastMessageAtDesc(userId, userId);
    Map<UUID, User> userById = new HashMap<>();
    users
        .findAllById(
            mine.stream()
                .flatMap(c -> java.util.stream.Stream.of(c.getInitiatorId(), c.getRecipientId()))
                .distinct()
                .toList())
        .forEach(u -> userById.put(u.getId(), u));
    Map<UUID, Listing> listingById = new HashMap<>();
    listings
        .findAllById(mine.stream().map(Conversation::getListingId).filter(Objects::nonNull).toList())
        .forEach(l -> listingById.put(l.getId(), l));

    List<ConversationResponse> out = new ArrayList<>();
    for (Conversation c : mine) {
      UUID otherId = userId.equals(c.getInitiatorId()) ? c.getRecipientId() : c.getInitiatorId();
      User other = userById.get(otherId);
      List<Message> thread =
          messages.findByConversationIdAndDeletedAtIsNullOrderByCreatedAtAsc(c.getId());
      Message last = thread.isEmpty() ? null : thread.get(thread.size() - 1);
      long unread =
          thread.stream().filter(m -> m.getReadAt() == null && !m.getSenderId().equals(userId)).count();
      Listing listing = c.getListingId() == null ? null : listingById.get(c.getListingId());
      out.add(
          new ConversationResponse(
              c.getId(),
              otherId,
              other == null ? "Member" : other.getName(),
              other == null ? null : other.getImage(),
              c.getStatus().name(),
              userId.equals(c.getInitiatorId()),
              c.getListingId(),
              listing == null ? null : listing.getTitle(),
              listing == null ? null : listing.getRentMonthly(),
              last == null ? null : (last.getBody().length() > 60 ? last.getBody().substring(0, 57) + "…" : last.getBody()),
              c.getLastMessageAt(),
              unread));
    }
    return out;
  }

  @Transactional(readOnly = true)
  public List<MessageResponse> messagesOf(UUID userId, UUID conversationId, Instant after) {
    Conversation conversation = participantConversation(userId, conversationId);
    return messages.findByConversationIdAndDeletedAtIsNullOrderByCreatedAtAsc(conversation.getId()).stream()
        .filter(m -> after == null || m.getCreatedAt().isAfter(after))
        .map(m -> new MessageResponse(m.getId(), m.getSenderId(), m.getBody(), m.getReadAt(), m.getCreatedAt()))
        .toList();
  }

  Conversation participantConversation(UUID userId, UUID conversationId) {
    Conversation conversation =
        conversations
            .findById(conversationId)
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
    if (!userId.equals(conversation.getInitiatorId()) && !userId.equals(conversation.getRecipientId())) {
      throw ApiException.forbidden("Not your conversation");
    }
    return conversation;
  }

  private void ensureNotBlocked(UUID a, UUID b) {
    if (blocks.findById(new UserBlock.Key(a, b)).isPresent()
        || blocks.findById(new UserBlock.Key(b, a)).isPresent()) {
      throw ApiException.forbidden("You can't message this member");
    }
  }
}
