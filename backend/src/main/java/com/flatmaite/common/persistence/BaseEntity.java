package com.flatmaite.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Base for all UUID-keyed entities. Ids are assigned in @PrePersist (not @GeneratedValue) so the
 * deterministic seed can supply stable UUIDs and re-run idempotently via merge semantics.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

  @Id private UUID id;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void assignIdIfMissing() {
    if (id == null) {
      id = UUID.randomUUID();
    }
  }
}
