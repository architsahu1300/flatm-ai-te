package com.flatmaite.listing;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingRepository extends JpaRepository<Listing, java.util.UUID> {
  @EntityGraph(attributePaths = {"images", "amenities"})
  java.util.List<Listing> findWithAssetsByIdIn(java.util.Collection<java.util.UUID> ids);

  java.util.List<Listing> findByListerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(java.util.UUID listerId);

  java.util.Optional<Listing> findByIdAndDeletedAtIsNull(java.util.UUID id);
}
