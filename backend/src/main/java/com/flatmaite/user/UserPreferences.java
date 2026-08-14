package com.flatmaite.user;

import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.RoomType;
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

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreferences extends BaseEntity {

  @Column(nullable = false, unique = true)
  private UUID userId;

  private Integer budgetMin;
  private Integer budgetMax;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private UUID[] localityIds = new UUID[0];

  private LocalDate moveInFrom;
  private LocalDate moveInTo;
  private Short leaseMonthsMin;
  private Short leaseMonthsMax;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private RoomType roomType;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private String[] furnishing = new String[0];

  private Short bhkMin;
  private Short bhkMax;
  private Integer depositMax;

  @Column(nullable = false)
  @Builder.Default
  private boolean parkingNeeded = false;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private GenderPreference genderPref = GenderPreference.ANY;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private String[] amenities = new String[0];

  private String notes;
}
