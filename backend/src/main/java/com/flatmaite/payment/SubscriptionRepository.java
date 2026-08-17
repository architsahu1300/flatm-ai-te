package com.flatmaite.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, java.util.UUID> {
  java.util.List<Subscription> findByUserId(java.util.UUID userId);

  java.util.Optional<Subscription> findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(
      java.util.UUID userId, com.flatmaite.common.domain.SubscriptionStatus status);
}
