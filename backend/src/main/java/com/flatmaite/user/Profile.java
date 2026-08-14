package com.flatmaite.user;

import com.flatmaite.common.domain.CleanlinessLevel;
import com.flatmaite.common.domain.CookingFrequency;
import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.DrinkingHabit;
import com.flatmaite.common.domain.Gender;
import com.flatmaite.common.domain.GuestFrequency;
import com.flatmaite.common.domain.OccupationType;
import com.flatmaite.common.domain.PartyFrequency;
import com.flatmaite.common.domain.PetsStance;
import com.flatmaite.common.domain.SleepSchedule;
import com.flatmaite.common.domain.SmokingHabit;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.common.domain.WfhFrequency;
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
@Table(name = "profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile extends BaseEntity {

  @Column(nullable = false, unique = true)
  private UUID userId;

  private LocalDate dateOfBirth;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private Gender gender;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private OccupationType occupation;

  private String occupationDetail;
  private String companyOrCollege;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private String[] languages = new String[0];

  private String bio;
  private UUID currentLocalityId;
  private String hometown;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private SmokingHabit smoking;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private DrinkingHabit drinking;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private Diet diet;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private PetsStance pets;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private SleepSchedule sleepSchedule;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private WfhFrequency wfhFrequency;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private CleanlinessLevel cleanliness;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private SocialStyle socialStyle;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private PartyFrequency partyFrequency;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private GuestFrequency guestFrequency;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private CookingFrequency cookingFrequency;

  private String householdPref;

  @Column(nullable = false)
  @Builder.Default
  private short profileCompleteness = 0;
}
