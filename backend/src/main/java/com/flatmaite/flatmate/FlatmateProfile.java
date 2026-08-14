package com.flatmaite.flatmate;

import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Public flatmate discovery card. Lifestyle facts live on {@link com.flatmaite.user.Profile} and
 * are joined at scoring time. Embedding + tsvector columns are JdbcTemplate-only.
 */
@Entity
@Table(name = "flatmate_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlatmateProfile extends BaseEntity {

  @Column(nullable = false, unique = true)
  private UUID userId;

  @Column(nullable = false)
  private String headline;

  @Column(nullable = false)
  @Builder.Default
  private String about = "";

  @Column(nullable = false)
  @Builder.Default
  private boolean isActive = false;

  @Column(nullable = false)
  @Builder.Default
  private boolean hasFlat = false;

  private Integer budgetMin;
  private Integer budgetMax;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private UUID[] localityIds = new UUID[0];

  private LocalDate moveInFrom;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private GenderPreference genderPref = GenderPreference.ANY;

  private String embeddingTextHash;
}
