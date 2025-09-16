package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class IntradayCandleResponse {

    private CandleData data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CandleData {
        // Upstox returns array-of-arrays; keep it raw and convert explicitly
        private List<List<Object>> candles;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({ "timestamp","open","high","low","close","volume","openInterest" })
    public static class Candle {
        // Keep timestamp as Instant for downstream logic; convert from ISO string
        private Instant timestamp;
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private Double openInterest; // optional/nullable
    }

    /**
     * Convert array-of-arrays payload to typed Candle list.
     * Expected order per Upstox: [ts, open, high, low, close, volume, oi?]
     */
    public List<Candle> toCandleList() {
        List<Candle> out = new ArrayList<>();
        if (data == null || data.getCandles() == null) return out;

        for (List<Object> row : data.getCandles()) {
            if (row == null || row.isEmpty()) continue;
            try {
                // 0) timestamp (ISO string with offset, e.g. 2025-09-15T15:25:00+05:30)
                Instant ts = null;
                Object v0 = get(row, 0);
                if (v0 instanceof String) {
                    ts = OffsetDateTime.parse((String) v0).toInstant();
                } else if (v0 instanceof Number) {
                    // If you ever receive epoch millis/seconds
                    long n = ((Number) v0).longValue();
                    // assume millis if it looks large, else seconds
                    ts = (n > 3_000_000_000L) ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
                }

                double open = toD(get(row, 1));
                double high = toD(get(row, 2));
                double low = toD(get(row, 3));
                double close = toD(get(row, 4));
                long volume = toL(get(row, 5));
                Double oi = row.size() > 6 ? toDObj(get(row, 6)) : null;

                out.add(new Candle(ts, open, high, low, close, volume, oi));
            } catch (Throwable ignore) {
                // Skip bad row safely
            }
        }
        return out;
    }

    // ---- tiny safe-casting helpers ----
    private static Object get(List<Object> row, int idx) {
        return (idx < row.size() ? row.get(idx) : null);
    }

    private static double toD(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) return Double.parseDouble((String) o);
        return 0.0;
    }

    private static Double toDObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) return Double.valueOf((String) o);
        return null;
    }

    private static long toL(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) return Long.parseLong((String) o);
        return 0L;
    }
}
