package com.flatmaite.saved;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedListingRepository extends JpaRepository<SavedListing, SavedListing.Key> {
  java.util.List<SavedListing> findByKeyUserIdOrderByCreatedAtDesc(java.util.UUID userId);
}
