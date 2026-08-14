package com.flatmaite.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, java.util.UUID> {
  java.util.List<Order> findByUserIdOrderByCreatedAtDesc(java.util.UUID userId);
}
