package com.flatmaite.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.common.domain.NotificationType;
import com.flatmaite.listing.ListingFilters;
import com.flatmaite.listing.ListingQueryService;
import com.flatmaite.saved.SavedSearch;
import com.flatmaite.saved.SavedSearchRepository;
import com.flatmaite.search.HybridRetriever;
import com.flatmaite.search.SearchIntent;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cron-shaped saved-search alert delivery. Every run, each alerts-enabled saved search is replayed
 * as a hard-filter count over listings created since its last run; fresh matches become one
 * IN_APP notification plus a (mock) email. Interval is intentionally short for the MVP demo —
 * production would honor alert_frequency per search.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SavedSearchAlertRunner {

  private final SavedSearchRepository savedSearches;
  private final NotificationRepository notifications;
  private final EmailProvider email;
  private final HybridRetriever retriever;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  @Scheduled(initialDelay = 120_000, fixedDelay = 900_000)
  @Transactional
  public void run() {
    List<SavedSearch> due = savedSearches.findByAlertsEnabledTrue();
    if (due.isEmpty()) {
      return;
    }
    int alerted = 0;
    for (SavedSearch search : due) {
      try {
        alerted += runOne(search) ? 1 : 0;
      } catch (Exception e) {
        // one broken intent must not stall the whole sweep
        log.warn("saved-search alert failed for {}: {}", search.getId(), e.getMessage());
      }
    }
    log.info("saved-search alerts: {} searches checked, {} alerted", due.size(), alerted);
  }

  private boolean runOne(SavedSearch search) throws Exception {
    SearchIntent intent = objectMapper.readValue(search.getIntent(), SearchIntent.class);
    ListingFilters filters = retriever.toFilters(intent);

    Instant since =
        search.getLastRunAt() != null ? search.getLastRunAt() : Instant.now().minus(Duration.ofDays(1));
    Map<String, Object> params = new LinkedHashMap<>();
    String where = ListingQueryService.buildWhere(filters, params);
    params.put("since", java.sql.Timestamp.from(since));

    List<Map<String, Object>> fresh =
        jdbc.queryForList(
            """
            SELECT l.id, l.title
            FROM listings l
            LEFT JOIN properties p ON p.id = l.property_id
            WHERE %s AND l.created_at > :since
            ORDER BY l.created_at DESC
            LIMIT 5
            """
                .formatted(where),
            params);

    search.setLastRunAt(Instant.now());
    search.setLastResultCount(fresh.size());
    savedSearches.save(search);
    if (fresh.isEmpty()) {
      return false;
    }

    String top = String.valueOf(fresh.get(0).get("title"));
    String body =
        fresh.size() == 1
            ? "New match for \"%s\": %s".formatted(search.getName(), top)
            : "%d new matches for \"%s\" — including %s".formatted(fresh.size(), search.getName(), top);
    notifications.save(
        Notification.builder()
            .userId(search.getUserId())
            .type(NotificationType.SAVED_SEARCH_ALERT)
            .title("New matches for your saved search")
            .body(body)
            .data("{\"savedSearchId\":\"" + search.getId() + "\"}")
            .sentAt(Instant.now())
            .build());

    String userEmail =
        jdbc.queryForObject(
            "SELECT email FROM users WHERE id = :id",
            Map.of("id", search.getUserId()),
            String.class);
    if (userEmail != null) {
      email.send(userEmail, "New matches for \"" + search.getName() + "\"", body);
    }
    return true;
  }
}
