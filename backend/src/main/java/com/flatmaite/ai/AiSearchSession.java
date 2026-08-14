package com.flatmaite.ai;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_search_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiSearchSession extends BaseEntity {

  private UUID userId;
  private String anonSessionId;

  /** Latest merged SearchIntent JSON. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String currentIntent;

  /** [{query, intentAfter, ts}] capped at 10 turns. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  @Builder.Default
  private String turns = "[]";

  /** Positional reference targets for "compare these three" / "the second one". */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private UUID[] lastResultIds = new UUID[0];

  @Column(nullable = false)
  private Instant expiresAt;
}
