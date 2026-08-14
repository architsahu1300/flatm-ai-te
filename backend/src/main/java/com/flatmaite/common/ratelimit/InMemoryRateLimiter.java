package com.flatmaite.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Token bucket per key. State resets on restart — acceptable for MVP; interface Redis-swappable. */
@Component
public class InMemoryRateLimiter implements RateLimiter {

  private static class Bucket {
    double tokens;
    long lastRefillNanos;
  }

  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofHours(2)).build();

  @Override
  public synchronized boolean tryAcquire(String key, int maxTokens, int refillSeconds) {
    Bucket bucket =
        buckets.get(
            key,
            k -> {
              Bucket b = new Bucket();
              b.tokens = maxTokens;
              b.lastRefillNanos = System.nanoTime();
              return b;
            });
    long now = System.nanoTime();
    double refillRatePerNano = 1.0 / (refillSeconds * 1_000_000_000.0);
    bucket.tokens =
        Math.min(maxTokens, bucket.tokens + (now - bucket.lastRefillNanos) * refillRatePerNano);
    bucket.lastRefillNanos = now;
    if (bucket.tokens >= 1) {
      bucket.tokens -= 1;
      return true;
    }
    return false;
  }
}
