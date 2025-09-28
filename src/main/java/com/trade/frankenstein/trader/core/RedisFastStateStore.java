package com.trade.frankenstein.trader.core;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis implementation using StringRedisTemplate.
 * Keys are prefixed with the provided prefix (e.g., "tf:").
 */
public final class RedisFastStateStore implements FastStateStore {

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisFastStateStore(StringRedisTemplate redis, String prefix) {
        if (redis == null) {
            throw new IllegalArgumentException("redis must not be null");
        }
        this.redis = redis;
        this.prefix = prefix == null ? "" : prefix;
    }

    private String k(String key) {
        return prefix + key;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        final String k = k(key);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            redis.opsForValue().set(k, value);
        } else {
            redis.opsForValue().set(k, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(k(key)));
    }

    @Override
    public void delete(String key) {
        redis.delete(k(key));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        final String k = k(key);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            Boolean ok = redis.opsForValue().setIfAbsent(k, value);
            return Boolean.TRUE.equals(ok);
        } else {
            Boolean ok = redis.opsForValue().setIfAbsent(k, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(ok);
        }
    }

    @Override
    public long incr(String key, Duration ttlIfNew) {
        final String k = k(key);
        Long val = redis.opsForValue().increment(k);
        if (val != null && val == 1L && ttlIfNew != null && !ttlIfNew.isZero() && !ttlIfNew.isNegative()) {
            // set TTL only when the key is created to emulate rolling window
            redis.expire(k, ttlIfNew.toMillis(), TimeUnit.MILLISECONDS);
        }
        return val == null ? 0L : val;
    }
}
