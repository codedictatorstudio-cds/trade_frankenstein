package com.trade.frankenstein.trader.service;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free rolling window estimator for p95 over the last N samples.
 * Stores up to N recent latencies (ms) and computes percentile on demand.
 * Good enough for UI chips; avoids heavy dependencies.
 */
public final class PercentileWindow {
    private final long[] buf;
    private final AtomicInteger idx = new AtomicInteger(0);
    private volatile int count = 0;

    public PercentileWindow(int capacity) {
        if (capacity < 10) capacity = 10;
        this.buf = new long[capacity];
    }

    public void record(long valueMs) {
        if (valueMs < 0) valueMs = 0;
        int i = idx.getAndIncrement();
        if (i < 0) i = -i; // should not happen
        int slot = i % buf.length;
        buf[slot] = valueMs;
        int c = count;
        if (c < buf.length) count = c + 1;
    }

    /** Return p at 0..100 (e.g., 95) or -1 if not enough data. */
    public long percentile(int p) {
        int c = count;
        if (c <= 0) return -1;
        long[] copy = Arrays.copyOf(buf, c);
        Arrays.sort(copy, 0, c);
        int rank = (int)Math.ceil((p / 100.0) * c) - 1;
        if (rank < 0) rank = 0;
        if (rank >= c) rank = c - 1;
        return copy[rank];
    }
}
