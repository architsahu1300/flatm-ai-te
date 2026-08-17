package com.flatmaite.saved;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, java.util.UUID> {
  java.util.List<SavedSearch> findByUserIdOrderByUpdatedAtDesc(java.util.UUID userId);

  java.util.List<SavedSearch> findByAlertsEnabledTrue();
}
