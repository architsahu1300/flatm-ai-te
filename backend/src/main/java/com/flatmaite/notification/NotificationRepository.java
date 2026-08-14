package com.flatmaite.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, java.util.UUID> {
  java.util.List<Notification> findByUserIdOrderByCreatedAtDesc(java.util.UUID userId);

  long countByUserIdAndReadAtIsNull(java.util.UUID userId);
}
