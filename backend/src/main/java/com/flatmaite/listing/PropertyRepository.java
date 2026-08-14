package com.flatmaite.listing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, java.util.UUID> {
  java.util.List<Property> findByOwnerId(java.util.UUID ownerId);
}
