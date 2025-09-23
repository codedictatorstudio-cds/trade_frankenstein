package com.trade.frankenstein.trader.core;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class RedisFastStateStore implements FastStateStore {

    private final StringRedisTemplate redis;
    private final String prefix;

    private String k(String key) {
        return prefix + key;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(k(key), value, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<String> get(String key) {
        String v = redis.opsForValue().get(k(key));
        return Optional.ofNullable(v);
    }

    @Override
    public void delete(String key) {
        redis.delete(k(key));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(k(key), value, ttl.toMillis(), TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public long incr(String key, Duration ttlIfNew) {
        Long val = redis.opsForValue().increment(k(key));
        // ensure rolling window by setting TTL when first created
        if (val != null && val == 1L && ttlIfNew != null) {
            redis.expire(k(key), ttlIfNew.toMillis(), TimeUnit.MILLISECONDS);
        }
        return val == null ? 0L : val;
    }
}
