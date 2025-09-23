package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.OptionType;
import com.upstox.api.GetMarketQuoteOptionGreekResponseV3;
import com.upstox.api.GetOptionContractResponse;
import com.upstox.api.InstrumentData;
import com.upstox.api.MarketQuoteOptionGreekV3;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptionChainService {

    private final UpstoxService upstox;
    private final FastStateStore fast; // Redis or in-memory per your toggle

    // ---------------------------------------------------------------------------------
    // Rolling in-JVM buffers (strike-level deltas) to complement the Redis snapshot
    // ---------------------------------------------------------------------------------
    private final Map<String, Map<Integer, Long>> lastCeOiByStrike = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> lastPeOiByStrike = new ConcurrentHashMap<>();

    // =================================================================================
    // Core chain queries (real-time)
    // =================================================================================

    /**
     * List all contracts in a strike range (inclusive) for a given expiry.
     */
    public Result<List<InstrumentData>> listContractsByStrikeRange(
            String underlyingKey, LocalDate expiry, BigDecimal minStrike, BigDecimal maxStrike) {

        if (isBlank(underlyingKey) || expiry == null || minStrike == null || maxStrike == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, minStrike, maxStrike are required");
        }
        List<InstrumentData> all = fetchInstruments(underlyingKey, expiry);
        if (all.isEmpty()) return Result.ok(Collections.emptyList());

        final int minK = minStrike.setScale(0, RoundingMode.HALF_UP).intValue();
        final int maxK = maxStrike.setScale(0, RoundingMode.HALF_UP).intValue();

        List<InstrumentData> filtered = all.stream()
                .filter(c -> strikeInt(c) >= minK && strikeInt(c) <= maxK)
                .sorted(Comparator.comparingInt(OptionChainService::strikeInt))
                .collect(Collectors.toList());
        return Result.ok(filtered);
    }

    /**
     * Return up to {@code count} nearest expiries (>= today).
     * We probe upcoming Wed/Thu/Fri and keep the ones for which Upstox actually returns contracts.
     */
    public Result<List<LocalDate>> listNearestExpiries(String underlyingKey, int count) {
        if (isBlank(underlyingKey)) return Result.fail("BAD_REQUEST", "underlyingKey required");

        final LocalDate today = LocalDate.now();
        final LocalDate horizon = today.plusDays(56);
        Set<LocalDate> candidates = new LinkedHashSet<>();
        for (LocalDate d = today; !d.isAfter(horizon); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY || dow == DayOfWeek.FRIDAY) {
                candidates.add(d);
            }
        }

        List<LocalDate> found = new ArrayList<>();
        for (LocalDate d : candidates) {
            try {
                if (!fetchInstruments(underlyingKey, d).isEmpty()) {
                    found.add(d);
                    if (found.size() >= Math.max(1, count)) break;
                }
            } catch (Exception ex) {
                log.debug("Expiry probe failed for {}: {}", d, ex.toString());
            }
        }

        if (found.isEmpty()) return Result.fail("NOT_FOUND", "No upcoming expiries discovered for underlying");
        found.sort(Comparator.naturalOrder());
        return Result.ok(found.subList(0, Math.min(found.size(), Math.max(1, count))));
    }

    /**
     * Find a single contract by expiry, strike and CALL/PUT.
     */
    public Result<InstrumentData> findContract(
            String underlyingKey, LocalDate expiry, BigDecimal strike, OptionType type) {

        if (isBlank(underlyingKey) || expiry == null || strike == null || type == null) {
            return Result.fail("BAD_REQUEST", "params required");
        }
        List<InstrumentData> data = fetchInstruments(underlyingKey, expiry);
        if (data.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        String side = optTypeCode(type); // "CE"/"PE"
        int k = strike.setScale(0, RoundingMode.HALF_UP).intValue();

        for (InstrumentData oi : data) {
            if (sideEquals(oi, side) && strikeInt(oi) == k) {
                return Result.ok(oi);
            }
        }
        return Result.fail("NOT_FOUND", "Contract not found");
    }

    // =================================================================================
    // Real-time metrics (PCR, Max Pain, Greeks snapshot)
    // =================================================================================

    /**
     * OI Put/Call ratio (PE OI / CE OI) for an expiry, from live greeks.
     */
    public Result<BigDecimal> getOiPcr(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        long ceOi = 0L, peOi = 0L;
        for (InstrumentData oi : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;
            long oiVal = safeLong(g.getOi());
            if (sideEquals(oi, "CE")) ceOi += oiVal;
            else if (sideEquals(oi, "PE")) peOi += oiVal;
        }
        if (ceOi == 0L) return Result.fail("DIV_BY_ZERO", "Call OI zero");
        BigDecimal pcr = BigDecimal.valueOf(peOi).divide(BigDecimal.valueOf(ceOi), 6, RoundingMode.HALF_UP);
        return Result.ok(pcr);
    }

    /**
     * Volume Put/Call ratio (PE Vol / CE Vol) for an expiry, from live greeks volume.
     */
    public Result<BigDecimal> getVolumePcr(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        long ceVol = 0L, peVol = 0L;
        for (InstrumentData oi : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;
            long vol = safeLong(g.getVolume());
            if (sideEquals(oi, "CE")) ceVol += vol;
            else if (sideEquals(oi, "PE")) peVol += vol;
        }
        if (ceVol == 0L) return Result.fail("DIV_BY_ZERO", "Call volume zero");
        BigDecimal pcr = BigDecimal.valueOf(peVol).divide(BigDecimal.valueOf(ceVol), 6, RoundingMode.HALF_UP);
        return Result.ok(pcr);
    }


    /**
     * Raw greeks map (keyed by instrument_key) for all contracts of an expiry.
     */
    public Result<Map<String, MarketQuoteOptionGreekV3>> getGreeksForExpiry(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");
        return Result.ok(greeks);
    }

    // =================================================================================
    // OI Δ, IV Percentile, IV Skew (SDK-safe)
    // =================================================================================

    /**
     * Top OI increases by strike for a given expiry and option side.
     * Returns LinkedHashMap<strike, deltaOi> sorted by descending delta, limited to {@code limit}.
     */
    public Result<LinkedHashMap<Integer, Long>> topOiChange(
            String underlyingKey, LocalDate expiry, OptionType type, int limit) {

        if (isBlank(underlyingKey) || expiry == null || type == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, type required");
        }
        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        String side = optTypeCode(type); // "CE" / "PE"
        Map<Integer, Long> curr = oiByStrike(instruments, greeks, side);

        final String key = chainKey(underlyingKey, expiry);
        Map<String, Map<Integer, Long>> cache = "CE".equals(side) ? lastCeOiByStrike : lastPeOiByStrike;
        Map<Integer, Long> prev = cache.get(key);

        // compute positive deltas (curr - prev)
        Map<Integer, Long> deltas = new HashMap<>();
        for (Map.Entry<Integer, Long> e : curr.entrySet()) {
            long before = (prev == null) ? 0L : prev.getOrDefault(e.getKey(), 0L);
            long d = e.getValue() - before;
            if (d > 0L) deltas.put(e.getKey(), d);
        }

        // update cache
        cache.put(key, curr);

        // sort desc by delta, limit, and return
        LinkedHashMap<Integer, Long> out = deltas.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, limit))
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue()),
                        LinkedHashMap::putAll);

        if (out.isEmpty()) return Result.fail("NO_CHANGE", "No positive OI increases");
        return Result.ok(out);
    }

    /**
     * IV percentile (0..100) for the given strike/type within the same expiry + side population.
     * Example: 90 means the strike's IV is higher than 90% of same-side strikes for that expiry.
     */
    public Result<BigDecimal> getIvPercentile(
            String underlyingKey, LocalDate expiry, BigDecimal strike, OptionType type) {

        if (isBlank(underlyingKey) || expiry == null || strike == null || type == null) {
            return Result.fail("BAD_REQUEST", "params required");
        }

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        String side = optTypeCode(type);
        int k = strike.setScale(0, RoundingMode.HALF_UP).intValue();

        // gather same-side IVs and find target IV
        List<BigDecimal> ivs = new ArrayList<>();
        BigDecimal targetIv = null;

        for (InstrumentData oi : instruments) {
            if (!sideEquals(oi, side)) continue;
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;

            BigDecimal iv = safeIv(g);
            if (iv == null) continue;

            ivs.add(iv);
            if (strikeInt(oi) == k) targetIv = iv;
        }

        if (ivs.isEmpty() || targetIv == null) return Result.fail("NOT_FOUND", "IVs not available for strike/type");

        int nLe = 0;
        for (BigDecimal v : ivs) if (v.compareTo(targetIv) <= 0) nLe++;
        BigDecimal pct = BigDecimal.valueOf(nLe * 100.0 / ivs.size()).setScale(2, RoundingMode.HALF_UP);
        return Result.ok(pct);
    }

    /**
     * Simple near-ATM IV skew: mean(PE IV) − mean(CE IV) across ±strikesEachSide around ATM.
     * Positive → puts richer than calls; Negative → calls richer.
     */
    public Result<BigDecimal> getIvSkew(
            String underlyingKey, LocalDate expiry, BigDecimal underlyingLtp,
            int strikesEachSide, int strikeStep) {

        if (isBlank(underlyingKey) || expiry == null || underlyingLtp == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, underlyingLtp required");
        }
        int step = (strikeStep <= 0) ? 50 : strikeStep;
        BigDecimal atm = computeAtmStrike(underlyingLtp, step);

        BigDecimal min = atm.subtract(BigDecimal.valueOf((long) step * strikesEachSide));
        BigDecimal max = atm.add(BigDecimal.valueOf((long) step * strikesEachSide));

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");
        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        // Collect IVs within the range by side
        List<BigDecimal> ceIvs = new ArrayList<>();
        List<BigDecimal> peIvs = new ArrayList<>();
        for (InstrumentData oi : instruments) {
            int k = strikeInt(oi);
            if (k < min.intValue() || k > max.intValue()) continue;

            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;

            BigDecimal iv = safeIv(g);
            if (iv == null) continue;

            if (sideEquals(oi, "CE")) ceIvs.add(iv);
            else if (sideEquals(oi, "PE")) peIvs.add(iv);
        }

        if (ceIvs.isEmpty() || peIvs.isEmpty()) {
            return Result.fail("NOT_FOUND", "Insufficient IV data near ATM");
        }

        BigDecimal ceMean = mean(ceIvs);
        BigDecimal peMean = mean(peIvs);
        return Result.ok(peMean.subtract(ceMean).setScale(4, RoundingMode.HALF_UP));
    }

    /**
     * Convenience overload: ±3 strikes, step=50 (NIFTY).
     */
    public Result<BigDecimal> getIvSkew(String underlyingKey, LocalDate expiry, BigDecimal underlyingLtp) {
        return getIvSkew(underlyingKey, expiry, underlyingLtp, 3, 50);
    }

    // =================================================================================
    // Fast snapshot (Step-3): total CE/PE OI per expiry with Redis TTL=10s
    // =================================================================================

    public static class OiSnapshot {
        public final BigDecimal totalCeOi;
        public final BigDecimal totalPeOi;
        public final Instant asOf;

        public OiSnapshot(BigDecimal totalCeOi, BigDecimal totalPeOi, Instant asOf) {
            this.totalCeOi = totalCeOi;
            this.totalPeOi = totalPeOi;
            this.asOf = asOf;
        }
    }

    /**
     * Latest OI snapshot (offset 0 = now, -1 = previous, etc.).
     * Uses Redis key tf:oi:{underlyingKey}:{yyyy-MM-dd} with 10s TTL for the "now" snapshot.
     * When offset != 0 we recompute live and do not cache historical points.
     */
    public Optional<OiSnapshot> getLatestOiSnapshot(String underlyingKey, LocalDate expiry, int offset) {
        if (isBlank(underlyingKey) || expiry == null) return Optional.empty();

        final String key = "oi:" + underlyingKey + ":" + expiry.format(DateTimeFormatter.ISO_DATE);

        try {
            if (offset == 0) {
                // 1) Try Redis
                Optional<String> cached = fast.get(key);
                if (cached.isPresent()) {
                    String[] parts = cached.get().split("\\|");
                    if (parts.length == 3) {
                        BigDecimal ce = new BigDecimal(parts[0]);
                        BigDecimal pe = new BigDecimal(parts[1]);
                        Instant asOf = Instant.ofEpochSecond(Long.parseLong(parts[2]));
                        return Optional.of(new OiSnapshot(ce, pe, asOf));
                    }
                }

                // 2) Compute live and cache
                OiSnapshot now = computeOiSnapshot(underlyingKey, expiry);
                if (now != null) {
                    String payload = now.totalCeOi.toPlainString() + "|" + now.totalPeOi.toPlainString() + "|" + now.asOf.getEpochSecond();
                    fast.put(key, payload, java.time.Duration.ofSeconds(10));
                    return Optional.of(now);
                }
                return Optional.empty();
            } else {
                // Historical step: recompute (no cache)
                OiSnapshot s = computeOiSnapshot(underlyingKey, expiry);
                return Optional.ofNullable(s);
            }
        } catch (Exception ex) {
            log.error("getLatestOiSnapshot failed: {}", ex.toString());
            return Optional.empty();
        }
    }

    private OiSnapshot computeOiSnapshot(String underlyingKey, LocalDate expiry) {
        try {
            List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
            if (instruments == null || instruments.isEmpty()) return null;

            Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
            if (greeks.isEmpty()) return null;

            long ceOi = 0L, peOi = 0L;
            for (InstrumentData oi : instruments) {
                MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
                if (g == null) continue;
                long val = safeLong(g.getOi());
                if (sideEquals(oi, "CE")) ceOi += val;
                else if (sideEquals(oi, "PE")) peOi += val;
            }
            return new OiSnapshot(BigDecimal.valueOf(ceOi), BigDecimal.valueOf(peOi), Instant.now().truncatedTo(ChronoUnit.SECONDS));
        } catch (Exception t) {
            log.error("computeOiSnapshot failed", t);
            return null;
        }
    }

    // =================================================================================
    // Utilities
    // =================================================================================

    /**
     * Round price to nearest step (e.g., 50 for NIFTY).
     */
    public BigDecimal computeAtmStrike(BigDecimal price, int step) {
        if (price == null) return null;
        int s = Math.max(1, step);
        BigDecimal STEP = BigDecimal.valueOf(s);
        BigDecimal half = STEP.divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
        BigDecimal mod = price.remainder(STEP);
        BigDecimal base = price.subtract(mod);
        return (mod.compareTo(half) >= 0) ? base.add(STEP) : base;
    }

    private List<InstrumentData> fetchInstruments(String underlyingKey, LocalDate expiry) {
        try {
            // Expecting underlyingKey like "NSE_INDEX|Nifty 50" to be handled properly inside UpstoxService.
            GetOptionContractResponse resp = upstox.getOptionInstrument(underlyingKey, expiry.toString());
            if (resp == null || resp.getData() == null) return Collections.emptyList();
            return resp.getData();
        } catch (Exception t) {
            log.error("fetchInstruments failed: {}", t.toString());
            return Collections.emptyList();
        }
    }

    /**
     * Call greeks in batches to respect CSV limits on the Upstox endpoint.
     */
    private Map<String, MarketQuoteOptionGreekV3> fetchGreeksMap(List<InstrumentData> instruments) {
        Map<String, MarketQuoteOptionGreekV3> out = new HashMap<>();
        if (instruments == null || instruments.isEmpty()) return out;

        List<String> keys = new ArrayList<>(instruments.size());
        for (InstrumentData oi : instruments) {
            String k = oi.getInstrumentKey();
            if (k != null && !k.trim().isEmpty()) keys.add(k);
        }
        if (keys.isEmpty()) return out;

        final int BATCH = 100; // raise if API allows larger CSV
        for (int i = 0; i < keys.size(); i += BATCH) {
            String csv = joinCsv(keys, i, Math.min(i + BATCH, keys.size()));
            try {
                GetMarketQuoteOptionGreekResponseV3 g = upstox.getOptionGreeks(csv);
                if (g != null && g.getData() != null) out.putAll(g.getData());
            } catch (Exception t) {
                log.error("fetchGreeksMap batch failed: {}", t.toString());
            }
        }
        return out;
    }

    private static String joinCsv(List<String> keys, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(',');
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String optTypeCode(OptionType t) {
        return switch (t) {
            case CALL -> "CE";
            case PUT -> "PE";
            default -> "";
        };
    }

    private static String chainKey(String underlyingKey, LocalDate expiry) {
        return underlyingKey + "|" + expiry;
    }

    // SDK-safe helpers
    private static int strikeInt(InstrumentData oi) {
        // Upstox SDK exposes strike as double; we treat strikes as discrete ints (50-step rounded elsewhere).
        double k = oi.getStrikePrice();
        return (int) Math.round(k);
    }

    private static boolean sideEquals(InstrumentData oi, String side) {
        // Many SDKs expose option side as "CE"/"PE" in a field sometimes named "underlyingType" for options.
        String s = oi.getUnderlyingType();
        return s != null && s.equalsIgnoreCase(side);
    }

    private Map<Integer, Long> oiByStrike(List<InstrumentData> instruments,
                                          Map<String, MarketQuoteOptionGreekV3> greeks,
                                          String side) {
        Map<Integer, Long> map = new HashMap<>();
        for (InstrumentData oi : instruments) {
            if (!sideEquals(oi, side)) continue;
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;
            int k = strikeInt(oi);
            long add = safeLong(g.getOi());
            map.put(k, map.getOrDefault(k, 0L) + add);
        }
        return map;
    }

    private static BigDecimal mean(List<BigDecimal> vals) {
        if (vals == null || vals.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : vals) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(vals.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * Safely read IV from greeks regardless of underlying numeric type.
     * Returns null if IV not present/parsable.
     */
    private static BigDecimal safeIv(MarketQuoteOptionGreekV3 g) {
        try {
            Number v = g.getIv();
            if (v == null) return null;
            return BigDecimal.valueOf(v.doubleValue());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static long safeLong(Number n) {
        if (n == null) return 0L;
        try {
            return Math.max(0L, n.longValue());
        } catch (Exception e) {
            return 0L;
        }
    }

    public Optional<MarketQuoteOptionGreekV3> getGreek(String instrumentKey) {
        try {
            Result<List<LocalDate>> expsRes = listNearestExpiries(Underlyings.NIFTY, 3);
            if (expsRes == null || !expsRes.isOk() || expsRes.get() == null) return Optional.empty();

            for (LocalDate exp : expsRes.get()) {
                Result<Map<String, MarketQuoteOptionGreekV3>> opt =
                        getGreeksForExpiry(Underlyings.NIFTY, exp);
                if (opt.isOk()) {
                    MarketQuoteOptionGreekV3 g = opt.get().get(instrumentKey);
                    if (g != null) return Optional.of(g);
                }
            }
        } catch (Exception ignored) {
            log.error("getGreek failed: {}", ignored);
        }
        return Optional.empty();
    }
}
