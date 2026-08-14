package com.flatmaite.common.ratelimit;

/**
 * Interface so the in-memory token bucket can be swapped for Redis without touching call sites.
 */
public interface RateLimiter {

  /**
   * @param bucket logical bucket, e.g. "auth:register:{ip}"
   * @param maxTokens burst capacity
   * @param refillSeconds seconds to refill one token
   * @return true if the call is allowed
   */
  boolean tryAcquire(String bucket, int maxTokens, int refillSeconds);
}
