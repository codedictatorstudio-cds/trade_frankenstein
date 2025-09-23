package com.trade.frankenstein.trader.core;


import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFastStateStore implements FastStateStore {

    private static class Entry { String v; long expAt; }
    private final Map<String, Entry> m = new ConcurrentHashMap<>();
    private final String prefix;

    public InMemoryFastStateStore(String prefix) { this.prefix = prefix; }

    private String k(String key) { return prefix + key; }
    private long now() { return Instant.now().toEpochMilli(); }
    private void clean(String key) {
        Entry e = m.get(key);
        if (e != null && e.expAt > 0 && e.expAt <= now()) m.remove(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        String kk = k(key);
        Entry e = new Entry();
        e.v = value;
        e.expAt = ttl == null ? 0 : now() + ttl.toMillis();
        m.put(kk, e);
    }

    @Override
    public Optional<String> get(String key) {
        String kk = k(key);
        clean(kk);
        Entry e = m.get(kk);
        return Optional.ofNullable(e == null ? null : e.v);
    }

    @Override
    public void delete(String key) {
        m.remove(k(key));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        String kk = k(key);
        clean(kk);
        final boolean[] wasAbsent = {false};
        m.compute(kk, (k, existing) -> {
            if (existing != null) return existing; // already present
            Entry e = new Entry();
            e.v = value;
            e.expAt = ttl == null ? 0 : now() + ttl.toMillis();
            wasAbsent[0] = true;
            return e;
        });
        return wasAbsent[0];
    }

    @Override
    public long incr(String key, Duration ttlIfNew) {
        return 0;
    }
}
