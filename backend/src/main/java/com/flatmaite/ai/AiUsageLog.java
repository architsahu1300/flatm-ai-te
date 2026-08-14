package com.flatmaite.ai;

import com.flatmaite.common.domain.AiFeature;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only token/cost ledger — the source of truth for daily AI quotas. */
@Entity
@Table(name = "ai_usage_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageLog {

  @Id private UUID id;

  private UUID userId;
  private String anonKey;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  private AiFeature feature;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String model;

  @Column(nullable = false)
  @Builder.Default
  private int promptTokens = 0;

  @Column(nullable = false)
  @Builder.Default
  private int completionTokens = 0;

  @Column(nullable = false)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  private Integer latencyMs;

  @Column(nullable = false)
  @Builder.Default
  private boolean cacheHit = false;

  @Column(nullable = false)
  @Builder.Default
  private boolean success = true;

  private String errorCode;
  private String requestHash;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void assignId() {
    if (id == null) {
      id = UUID.randomUUID();
    }
  }
}
