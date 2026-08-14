package com.flatmaite.ai;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSearchSessionRepository extends JpaRepository<AiSearchSession, java.util.UUID> {
  java.util.List<AiSearchSession> findByAnonSessionId(String anonSessionId);
}
