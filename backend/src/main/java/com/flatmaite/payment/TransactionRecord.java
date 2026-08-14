package com.flatmaite.payment;

import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionRecord extends BaseEntity {

  @Column(nullable = false)
  private UUID paymentId;

  /** charge | refund */
  @Column(nullable = false)
  private String type;

  @Column(nullable = false)
  private BigDecimal amount;

  private BigDecimal balanceAfter;
}
