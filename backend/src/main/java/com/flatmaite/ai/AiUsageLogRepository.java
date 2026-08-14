package com.flatmaite.ai;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, java.util.UUID> {
}
