package com.trade.frankenstein.trader.core;

import java.time.Duration;
import java.util.Optional;

public interface FastStateStore {

    // Cache
    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void delete(String key);

    // Atomic "set if absent" with TTL (idempotency / dedupe)
    boolean setIfAbsent(String key, String value, Duration ttl);

    // Counters with rolling TTL (rate limits)
    long incr(String key, Duration ttlIfNew);
}
