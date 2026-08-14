package com.flatmaite.payment;

import com.flatmaite.common.domain.OrderKind;
import com.flatmaite.common.domain.PaymentStatus;
import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends BaseEntity {

  @Column(nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  private OrderKind kind;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(nullable = false)
  @Builder.Default
  private String currency = "INR";

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private PaymentStatus status = PaymentStatus.CREATED;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  @Builder.Default
  private String metadata = "{}";
}
