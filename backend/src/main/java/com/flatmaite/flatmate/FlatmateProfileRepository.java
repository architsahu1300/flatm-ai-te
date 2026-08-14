package com.flatmaite.flatmate;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatmateProfileRepository extends JpaRepository<FlatmateProfile, java.util.UUID> {
  java.util.Optional<FlatmateProfile> findByUserId(java.util.UUID userId);
}
