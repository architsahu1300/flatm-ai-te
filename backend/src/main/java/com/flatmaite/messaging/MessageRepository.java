package com.flatmaite.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, java.util.UUID> {
  java.util.List<Message> findByConversationIdAndDeletedAtIsNullOrderByCreatedAtAsc(java.util.UUID conversationId);
}
