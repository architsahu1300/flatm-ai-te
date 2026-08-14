package com.flatmaite.report;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, java.util.UUID> {
  java.util.List<Report> findByStatusOrderByCreatedAtDesc(com.flatmaite.common.domain.ReportStatus status);
}
