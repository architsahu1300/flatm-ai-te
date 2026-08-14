package com.flatmaite.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.ai.AiSearchSession;
import com.flatmaite.ai.AiSearchSessionRepository;
import com.flatmaite.common.web.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Conversational state: current merged intent, turn history (capped 10), last result ids. */
@Service
@RequiredArgsConstructor
public class SearchSessionService {

  private static final int MAX_TURNS = 10;

  private final AiSearchSessionRepository sessions;
  private final ObjectMapper objectMapper;

  @SneakyThrows
  @Transactional
  public AiSearchSession start(UUID userId, String anonSessionId, SearchIntent intent, String query) {
    AiSearchSession session =
        AiSearchSession.builder()
            .userId(userId)
            .anonSessionId(anonSessionId)
            .currentIntent(objectMapper.writeValueAsString(intent))
            .turns(objectMapper.writeValueAsString(List.of(turn(query))))
            .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .build();
    return sessions.save(session);
  }

  @SneakyThrows
  @Transactional
  public AiSearchSession update(AiSearchSession session, SearchIntent intent, String query, List<UUID> resultIds) {
    session.setCurrentIntent(objectMapper.writeValueAsString(intent));
    List<Map<String, Object>> turns =
        objectMapper.readValue(session.getTurns(), new TypeReference<>() {});
    List<Map<String, Object>> updated = new ArrayList<>(turns);
    updated.add(turn(query));
    if (updated.size() > MAX_TURNS) {
      updated = updated.subList(updated.size() - MAX_TURNS, updated.size());
    }
    session.setTurns(objectMapper.writeValueAsString(updated));
    session.setLastResultIds(resultIds.toArray(UUID[]::new));
    session.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
    return sessions.save(session);
  }

  @Transactional
  public void storeResults(AiSearchSession session, List<UUID> resultIds) {
    session.setLastResultIds(resultIds.toArray(UUID[]::new));
    sessions.save(session);
  }

  /** Loads a live session the caller owns (by user id, or anon cookie for guests). */
  @Transactional(readOnly = true)
  public AiSearchSession requireOwned(UUID sessionId, UUID userId, String anonSessionId) {
    AiSearchSession session =
        sessions
            .findById(sessionId)
            .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> ApiException.notFound("Search session expired — start a new search"));
    boolean owned =
        (session.getUserId() != null && session.getUserId().equals(userId))
            || (session.getUserId() == null
                && session.getAnonSessionId() != null
                && session.getAnonSessionId().equals(anonSessionId));
    if (!owned) {
      throw ApiException.forbidden("Not your search session");
    }
    return session;
  }

  @SneakyThrows
  public SearchIntent intentOf(AiSearchSession session) {
    return objectMapper.readValue(session.getCurrentIntent(), SearchIntent.class);
  }

  private static Map<String, Object> turn(String query) {
    return Map.of("query", query, "ts", Instant.now().toString());
  }
}
