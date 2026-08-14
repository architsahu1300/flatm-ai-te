package com.flatmaite.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, java.util.UUID> {
  java.util.Optional<Profile> findByUserId(java.util.UUID userId);
}
