package com.trade.frankenstein.trader.core;

import java.time.Duration;
import java.util.Optional;

/**
 * A tiny abstraction over a fast key-value store with TTL semantics.
 * Used for feature flags, idempotency keys and simple rate counters.
 *
 * Contracts (Java 8):
 *  - All methods are thread-safe.
 *  - TTL of null or non-positive means "no expiry".
 *  - incr(...) creates the key with value=1 if absent or expired,
 *    and only sets TTL when the key is created (rolling window).
 */
public interface FastStateStore {

    // Basic KV with TTL
    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void delete(String key);

    // Atomic "set if absent" with TTL (idempotency / dedupe)
    boolean setIfAbsent(String key, String value, Duration ttl);

    // Atomic counter with rolling TTL (rate limits)
    long incr(String key, Duration ttlIfNew);
}
