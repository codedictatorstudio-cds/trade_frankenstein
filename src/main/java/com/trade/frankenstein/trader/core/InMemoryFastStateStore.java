package com.trade.frankenstein.trader.core;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory implementation of FastStateStore.
 * Intended for local/dev/testing only (single JVM).
 */
public final class InMemoryFastStateStore implements FastStateStore {

    private static final class Entry {
        String v;
        long expAtMillis; // 0 = no expiry
    }

    private final ConcurrentMap<String, Entry> map = new ConcurrentHashMap<>();
    private final String prefix; // e.g., "tf:"

    public InMemoryFastStateStore(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static boolean isExpired(Entry e, long now) {
        return e != null && e.expAtMillis > 0 && now >= e.expAtMillis;
    }

    private String k(String key) {
        return prefix + key;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        final String kk = k(key);
        final long n = now();
        final Entry e = new Entry();
        e.v = value;
        e.expAtMillis = (ttl == null || ttl.isZero() || ttl.isNegative()) ? 0L : (n + ttl.toMillis());
        map.put(kk, e);
    }

    @Override
    public Optional<String> get(String key) {
        final String kk = k(key);
        final long n = now();
        final Entry e = map.get(kk);
        if (e == null) return Optional.empty();
        if (isExpired(e, n)) {
            map.remove(kk, e);
            return Optional.empty();
        }
        return Optional.ofNullable(e.v);
    }

    @Override
    public void delete(String key) {
        map.remove(k(key));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        final String kk = k(key);
        final long n = now();

        for (; ; ) {
            final Entry existing = map.get(kk);
            if (existing != null) {
                if (isExpired(existing, n)) {
                    // try to replace expired with new entry
                    final Entry fresh = new Entry();
                    fresh.v = value;
                    fresh.expAtMillis = (ttl == null || ttl.isZero() || ttl.isNegative()) ? 0L : (n + ttl.toMillis());
                    if (map.replace(kk, existing, fresh)) {
                        return true;
                    } else {
                        // lost race; retry
                        continue;
                    }
                } else {
                    return false; // already present and not expired
                }
            } else {
                final Entry fresh = new Entry();
                fresh.v = value;
                fresh.expAtMillis = (ttl == null || ttl.isZero() || ttl.isNegative()) ? 0L : (n + ttl.toMillis());
                if (map.putIfAbsent(kk, fresh) == null) {
                    return true;
                }
                // lost race; retry
            }
        }
    }

    @Override
    public long incr(String key, Duration ttlIfNew) {
        final String kk = k(key);
        final long n = now();

        for (; ; ) {
            Entry cur = map.get(kk);
            if (cur == null || isExpired(cur, n)) {
                final Entry fresh = new Entry();
                fresh.v = "1";
                fresh.expAtMillis = (ttlIfNew == null || ttlIfNew.isZero() || ttlIfNew.isNegative())
                        ? 0L : (n + ttlIfNew.toMillis());
                if (cur == null) {
                    if (map.putIfAbsent(kk, fresh) == null) {
                        return 1L;
                    }
                } else {
                    if (map.replace(kk, cur, fresh)) {
                        return 1L;
                    }
                }
                // lost race; retry
                continue;
            }

            // parse and increment
            long val;
            try {
                val = Long.parseLong(cur.v);
            } catch (NumberFormatException ex) {
                val = 0L;
            }
            final Entry next = new Entry();
            next.v = Long.toString(val + 1L);
            // preserve existing expiry window
            next.expAtMillis = cur.expAtMillis;

            if (map.replace(kk, cur, next)) {
                return val + 1L;
            }
            // lost race; retry
        }
    }
}
