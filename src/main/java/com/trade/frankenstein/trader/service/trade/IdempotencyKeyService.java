package com.trade.frankenstein.trader.service.trade;

import com.trade.frankenstein.trader.core.FastStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class IdempotencyKeyService {

    @Autowired(required = false)
    private FastStateStore fastStateStore;

    // Fallback in-memory store
    private final ConcurrentMap<String, Long> inMemoryKeys = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(10);

    public boolean acquire(String key) {
        if (fastStateStore != null) {
            try {
                return fastStateStore.setIfAbsent("idempotency:" + key, "1", TTL);
            } catch (Throwable t) {
                log.warn("FastStateStore failed for idempotency key: {}, falling back to in-memory", key, t);
            }
        }

        // Fallback to in-memory with timestamp-based TTL
        long now = System.currentTimeMillis();
        long expiry = now + TTL.toMillis();

        Long existing = inMemoryKeys.putIfAbsent(key, expiry);
        if (existing == null) {
            return true; // Successfully acquired
        }

        // Check if existing key has expired
        if (existing < now) {
            inMemoryKeys.put(key, expiry);
            return true;
        }

        return false; // Key already exists and not expired
    }

    // Cleanup expired keys periodically
    public void cleanup() {
        long now = System.currentTimeMillis();
        inMemoryKeys.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
