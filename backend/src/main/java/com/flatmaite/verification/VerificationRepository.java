package com.flatmaite.verification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRepository extends JpaRepository<Verification, java.util.UUID> {
  java.util.List<Verification> findByUserId(java.util.UUID userId);

  java.util.List<Verification> findByPropertyId(java.util.UUID propertyId);

  java.util.List<Verification> findByUserIdIn(java.util.Collection<java.util.UUID> userIds);
}
