package com.flatmaite.saved;

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
@Table(name = "saved_searches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedSearch extends BaseEntity {

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String name;

  /** Serialized SearchIntent JSON — replayable through the search pipeline. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String intent;

  @Column(nullable = false)
  @Builder.Default
  private boolean alertsEnabled = false;

  @Column(nullable = false)
  @Builder.Default
  private String alertFrequency = "daily";

  private Instant lastRunAt;
  private Integer lastResultCount;
}
