package com.flatmaite.search;

import com.flatmaite.ai.AiUsageLog;
import com.flatmaite.ai.AiUsageLogRepository;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.AiFeature;
import com.flatmaite.common.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DB-backed daily quotas (survive restarts — this is the money-protecting control) + the
 * append-only usage ledger. Token counts for OpenAI calls are conservative estimates (chars/4);
 * cost comes from a static price table.
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

  /** USD per 1M tokens: {input, output}. */
  private static final Map<String, double[]> PRICES =
      Map.of(
          "gpt-4o-mini", new double[] {0.15, 0.60},
          "gpt-4o", new double[] {2.50, 10.00},
          "text-embedding-3-small", new double[] {0.02, 0},
          "keyword-parser", new double[] {0, 0},
          "breakdown-template", new double[] {0, 0});

  private final AiUsageLogRepository usageLog;
  private final JdbcTemplate jdbc;
  private final FlatmaiteProperties props;

  public void checkQuota(UUID userId, String anonKey) {
    Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    if (userId != null) {
      Integer searches =
          jdbc.queryForObject(
              "SELECT count(*) FROM ai_usage_log WHERE user_id = ? AND created_at >= ?"
                  + " AND feature IN ('INTENT_EXTRACTION','REFINEMENT')",
              Integer.class,
              userId,
              java.sql.Timestamp.from(dayStart));
      if (searches != null && searches >= props.getAi().getDailySearchLimit()) {
        throw ApiException.tooManyRequests(
            "You've hit today's AI search limit (%d). Try the filters, or come back tomorrow."
                .formatted(props.getAi().getDailySearchLimit()));
      }
      BigDecimal cost =
          jdbc.queryForObject(
              "SELECT coalesce(sum(cost_usd),0) FROM ai_usage_log WHERE user_id = ? AND created_at >= ?",
              BigDecimal.class,
              userId,
              java.sql.Timestamp.from(dayStart));
      if (cost != null && cost.compareTo(props.getAi().getDailyCostLimitUsd()) >= 0) {
        throw ApiException.tooManyRequests("Daily AI budget reached — try again tomorrow.");
      }
    } else {
      Integer searches =
          jdbc.queryForObject(
              "SELECT count(*) FROM ai_usage_log WHERE anon_key = ? AND created_at >= ?"
                  + " AND feature IN ('INTENT_EXTRACTION','REFINEMENT')",
              Integer.class,
              anonKey,
              java.sql.Timestamp.from(dayStart));
      if (searches != null && searches >= props.getAi().getAnonDailySearchLimit()) {
        throw ApiException.tooManyRequests("Sign up to keep searching with AI — free accounts get a bigger daily allowance.");
      }
    }
  }

  public void log(
      UUID userId,
      String anonKey,
      AiFeature feature,
      String provider,
      String model,
      int promptTokens,
      int completionTokens,
      boolean cacheHit,
      boolean success,
      long latencyMs,
      String requestHash) {
    double[] price = PRICES.getOrDefault(model, new double[] {0, 0});
    BigDecimal cost =
        BigDecimal.valueOf(promptTokens / 1_000_000.0 * price[0] + completionTokens / 1_000_000.0 * price[1])
            .setScale(6, RoundingMode.HALF_UP);
    usageLog.save(
        AiUsageLog.builder()
            .userId(userId)
            .anonKey(anonKey)
            .feature(feature)
            .provider(provider)
            .model(model)
            .promptTokens(promptTokens)
            .completionTokens(completionTokens)
            .costUsd(cacheHit ? BigDecimal.ZERO : cost)
            .cacheHit(cacheHit)
            .success(success)
            .latencyMs((int) latencyMs)
            .requestHash(requestHash)
            .build());
  }

  public static int estimateTokens(String text) {
    return text == null ? 0 : Math.max(1, text.length() / 4);
  }
}
