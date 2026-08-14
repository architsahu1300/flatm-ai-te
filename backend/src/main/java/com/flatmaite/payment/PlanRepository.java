package com.flatmaite.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, java.util.UUID> {
  java.util.Optional<Plan> findBySlug(String slug);
}
