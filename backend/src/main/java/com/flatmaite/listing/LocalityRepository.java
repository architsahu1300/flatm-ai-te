package com.flatmaite.listing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalityRepository extends JpaRepository<Locality, java.util.UUID> {
  java.util.Optional<Locality> findByNameIgnoreCase(String name);
}
