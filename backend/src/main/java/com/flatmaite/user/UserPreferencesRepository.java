package com.flatmaite.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, java.util.UUID> {
  java.util.Optional<UserPreferences> findByUserId(java.util.UUID userId);
}
