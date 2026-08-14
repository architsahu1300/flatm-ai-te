package com.flatmaite.agreement;

import com.flatmaite.common.domain.AgreementStatus;
import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@Table(name = "agreements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agreement extends BaseEntity {

  private UUID listingId;
  private UUID propertyId;

  @Column(nullable = false)
  private UUID landlordId;

  @Column(nullable = false)
  private UUID createdBy;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private UUID[] tenantIds = new UUID[0];

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private AgreementStatus status = AgreementStatus.DRAFT;

  @Column(nullable = false)
  private int rentMonthly;

  @Column(nullable = false)
  private int deposit;

  @Column(nullable = false)
  @Builder.Default
  private short durationMonths = 11;

  @Column(nullable = false)
  @Builder.Default
  private short noticePeriodDays = 30;

  @Column(nullable = false)
  @Builder.Default
  private short lockInMonths = 6;

  @Column(nullable = false)
  @Builder.Default
  private BigDecimal annualEscalationPct = BigDecimal.ZERO;

  @Column(nullable = false)
  private LocalDate startDate;

  private String propertyAddress;

  @Column(nullable = false)
  @Builder.Default
  private String agreementState = "MH";

  /** [{id, title, body, source: standard|ai|custom}] */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  @Builder.Default
  private String clauses = "[]";

  @JdbcTypeCode(SqlTypes.JSON)
  private String stampDuty;

  /** [{userId, role, status, signedAt, providerRef}] — mock e-sign. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  @Builder.Default
  private String signatures = "[]";

  @Column(nullable = false)
  @Builder.Default
  private int currentVersion = 1;
}
