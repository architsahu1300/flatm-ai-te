package com.flatmaite.payment;

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
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice extends BaseEntity {

  @Column(nullable = false)
  private UUID orderId;

  @Column(nullable = false, unique = true)
  private String invoiceNumber;

  private String pdfPath;

  @Column(nullable = false)
  @Builder.Default
  private Instant issuedAt = Instant.now();
}
