package com.flatmaite.listing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AmenityRepository extends JpaRepository<Amenity, java.util.UUID> {
  java.util.List<Amenity> findBySlugIn(java.util.Collection<String> slugs);
}
