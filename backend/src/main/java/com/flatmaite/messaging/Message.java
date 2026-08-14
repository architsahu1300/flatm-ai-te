package com.flatmaite.messaging;

import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message extends BaseEntity {

  @Column(nullable = false)
  private UUID conversationId;

  @Column(nullable = false)
  private UUID senderId;

  @Column(nullable = false)
  private String body;

  private Instant readAt;
  private Instant deletedAt;
}
