package com.flatmaite.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, java.util.UUID> {
  java.util.List<Conversation> findByInitiatorIdOrRecipientIdOrderByLastMessageAtDesc(java.util.UUID a, java.util.UUID b);

  java.util.Optional<Conversation> findByInitiatorIdAndRecipientIdAndListingId(java.util.UUID i, java.util.UUID r, java.util.UUID l);
}
