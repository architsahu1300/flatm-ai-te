package com.flatmaite.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, java.util.UUID> {
  java.util.Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
