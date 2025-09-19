package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.model.upstox.OptionGreekResponse;
import com.trade.frankenstein.trader.model.upstox.OptionsInstruments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptionChainService {

    private final UpstoxService upstox;

    // ---------------------------------------------------------------------------------
    // Small rolling cache for OI deltas (per expiry & side) and OI snapshots
    // ---------------------------------------------------------------------------------
    private final Map<String, Map<Integer, Long>> lastCeOiByStrike = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> lastPeOiByStrike = new ConcurrentHashMap<>();
    private final Map<String, Deque<OiSnapshot>> oiSnapshotBuffer = new ConcurrentHashMap<>();

    // =================================================================================
    // Core chain queries (real-time)
    // =================================================================================

    /**
     * List all contracts in a strike range (inclusive) for a given expiry.
     */
    public Result<List<OptionsInstruments.OptionInstrument>> listContractsByStrikeRange(
            String underlyingKey, LocalDate expiry, BigDecimal minStrike, BigDecimal maxStrike) {

        if (isBlank(underlyingKey) || expiry == null || minStrike == null || maxStrike == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, minStrike, maxStrike are required");
        }
        List<OptionsInstruments.OptionInstrument> all = fetchInstruments(underlyingKey, expiry);
        List<OptionsInstruments.OptionInstrument> filtered = all.stream()
                .filter(c -> c.getStrike_price() >= minStrike.intValue() && c.getStrike_price() <= maxStrike.intValue())
                .sorted(Comparator.comparingInt(OptionsInstruments.OptionInstrument::getStrike_price))
                .collect(Collectors.toList());
        return Result.ok(filtered);
    }

    /**
     * Return up to {@code count} nearest expiries (>= today).
     */
    public Result<List<LocalDate>> listNearestExpiries(String underlyingKey, int count) {
        if (isBlank(underlyingKey)) return Result.fail("BAD_REQUEST", "underlyingKey required");

        // Probe likely weekly expiry days (Wed/Thu/Fri) over the next ~8 weeks
        final LocalDate today = LocalDate.now();
        final LocalDate max = today.plusDays(56);

        // Use a linked set to keep order & avoid duplicates
        Set<LocalDate> candidates = new LinkedHashSet<>();
        for (LocalDate d = today; !d.isAfter(max); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY || dow == DayOfWeek.FRIDAY) {
                candidates.add(d);
            }
        }

        // Ask Upstox for each candidate; keep the dates that actually have contracts
        List<LocalDate> found = new ArrayList<>();
        for (LocalDate d : candidates) {
            try {
                List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, d);
                if (!instruments.isEmpty()) {
                    found.add(d);
                    if (found.size() >= Math.max(1, count)) break; // early exit once we have enough
                }
            } catch (Exception ignored) {
                log.error("Moving to next date");
            }
        }

        if (found.isEmpty()) {
            return Result.fail("NOT_FOUND", "No upcoming expiries discovered for underlying");
        }
        // Sort & return up to count
        found.sort(Comparator.naturalOrder());
        return Result.ok(found.subList(0, Math.min(found.size(), Math.max(1, count))));
    }


    /**
     * Find a single contract by expiry, strike and CALL/PUT.
     */
    public Result<OptionsInstruments.OptionInstrument> findContract(
            String underlyingKey, LocalDate expiry, BigDecimal strike, OptionType type) {

        if (isBlank(underlyingKey) || expiry == null || strike == null || type == null) {
            return Result.fail("BAD_REQUEST", "params required");
        }
        List<OptionsInstruments.OptionInstrument> data = fetchInstruments(underlyingKey, expiry);
        String otp = optTypeCode(type); // "CE"/"PE"
        int k = strike.intValue();

        for (OptionsInstruments.OptionInstrument oi : data) {
            if (oi.getUnderlying_type() != null
                    && otp.equalsIgnoreCase(oi.getUnderlying_type())
                    && oi.getStrike_price() == k) {
                return Result.ok(oi);
            }
        }
        return Result.fail("NOT_FOUND", "Contract not found");
    }

    /**
     * Get a compact chain around ATM (± N strikes), using a given step (e.g. 50 for NIFTY).
     */
    public Result<List<OptionsInstruments.OptionInstrument>> getChainAroundAtm(
            String underlyingKey, LocalDate expiry, BigDecimal underlyingLtp,
            int strikesEachSide, int strikeStep) {

        if (isBlank(underlyingKey) || expiry == null || underlyingLtp == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, underlyingLtp required");
        }
        int step = strikeStep <= 0 ? 50 : strikeStep;
        BigDecimal atm = computeAtmStrike(underlyingLtp, step);

        BigDecimal min = atm.subtract(new BigDecimal(step * strikesEachSide));
        BigDecimal max = atm.add(new BigDecimal(step * strikesEachSide));

        return listContractsByStrikeRange(underlyingKey, expiry, min, max);
    }

    // =================================================================================
    // Real-time metrics (PCR, Max Pain, Greeks snapshot)
    // =================================================================================

    /**
     * OI Put/Call ratio (PE OI / CE OI) for an expiry, from live greeks.
     */
    public Result<BigDecimal> getOiPcr(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        long ceOi = 0L, peOi = 0L;
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
            if (g == null) continue;
            long oiVal = Math.max(0, g.getOi());
            if ("CE".equalsIgnoreCase(oi.getUnderlying_type())) ceOi += oiVal;
            else if ("PE".equalsIgnoreCase(oi.getUnderlying_type())) peOi += oiVal;
        }
        if (ceOi == 0L) return Result.fail("DIV_BY_ZERO", "Call OI zero");
        BigDecimal pcr = new BigDecimal(peOi).divide(new BigDecimal(ceOi), 6, RoundingMode.HALF_UP);
        return Result.ok(pcr);
    }

    /**
     * Volume Put/Call ratio (PE Vol / CE Vol) for an expiry, from live greeks volume.
     */
    public Result<BigDecimal> getVolumePcr(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        long ceVol = 0L, peVol = 0L;
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
            if (g == null) continue;
            long vol = Math.max(0, g.getVolume());
            if ("CE".equalsIgnoreCase(oi.getUnderlying_type())) ceVol += vol;
            else if ("PE".equalsIgnoreCase(oi.getUnderlying_type())) peVol += vol;
        }
        if (ceVol == 0L) return Result.fail("DIV_BY_ZERO", "Call volume zero");
        BigDecimal pcr = new BigDecimal(peVol).divide(new BigDecimal(ceVol), 6, RoundingMode.HALF_UP);
        return Result.ok(pcr);
    }

    /**
     * Max Pain strike (heuristic: strike with max combined OI across CE+PE).
     */
    public Result<BigDecimal> getMaxPainStrike(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        Map<Integer, Long> strikeSumOi = new HashMap<>();
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
            if (g == null) continue;
            int k = oi.getStrike_price();
            long prev = strikeSumOi.getOrDefault(k, 0L);
            strikeSumOi.put(k, prev + Math.max(0, g.getOi()));
        }
        if (strikeSumOi.isEmpty()) return Result.fail("NOT_FOUND", "No strike OI");

        int bestStrike = 0;
        long bestOi = Long.MIN_VALUE;
        for (Map.Entry<Integer, Long> e : strikeSumOi.entrySet()) {
            if (e.getValue() > bestOi) {
                bestOi = e.getValue();
                bestStrike = e.getKey();
            }
        }
        return Result.ok(new BigDecimal(bestStrike));
    }

    /**
     * Raw greeks map (keyed by instrument_key) for all contracts of an expiry.
     */
    public Result<Map<String, OptionGreekResponse.OptionGreek>> getGreeksForExpiry(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");
        return Result.ok(greeks);
    }

    // =================================================================================
    // NEW — OI Δ, IV Percentile, IV Skew
    // =================================================================================

    /**
     * Top OI increases by strike for a given expiry and option side.
     * Returns a LinkedHashMap<strike, deltaOi> sorted by descending delta, limited to {@code limit}.
     */
    public Result<LinkedHashMap<Integer, Long>> topOiChange(
            String underlyingKey, LocalDate expiry, OptionType type, int limit) {

        if (isBlank(underlyingKey) || expiry == null || type == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, type required");
        }
        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        String side = optTypeCode(type); // "CE" / "PE"
        Map<Integer, Long> curr = oiByStrike(instruments, greeks, side);

        final String key = underlyingKey + "|" + expiry;
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

        // sort desc by delta, limit, and return a stable LinkedHashMap
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

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        String side = optTypeCode(type);
        int k = strike.intValue();

        // gather same-side IVs and find target IV
        List<BigDecimal> ivs = new ArrayList<>();
        BigDecimal targetIv = null;

        for (OptionsInstruments.OptionInstrument oi : instruments) {
            if (!side.equalsIgnoreCase(oi.getUnderlying_type())) continue;
            OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
            if (g == null) continue;
            BigDecimal iv = safeIv(g);
            if (iv == null) continue;

            ivs.add(iv);
            if (oi.getStrike_price() == k) targetIv = iv;
        }

        if (ivs.isEmpty() || targetIv == null) return Result.fail("NOT_FOUND", "IVs not available for strike/type");

        // percentile = % of values <= target
        int nLe = 0;
        for (BigDecimal v : ivs) if (v.compareTo(targetIv) <= 0) nLe++;
        BigDecimal pct = new BigDecimal(nLe * 100.0 / ivs.size()).setScale(2, RoundingMode.HALF_UP);
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
        int step = strikeStep <= 0 ? 50 : strikeStep;
        BigDecimal atm = computeAtmStrike(underlyingLtp, step);

        BigDecimal min = atm.subtract(new BigDecimal(step * strikesEachSide));
        BigDecimal max = atm.add(new BigDecimal(step * strikesEachSide));

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");
        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        // Collect IVs within the range by side
        List<BigDecimal> ceIvs = new ArrayList<>();
        List<BigDecimal> peIvs = new ArrayList<>();
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            int k = oi.getStrike_price();
            if (k < min.intValue() || k > max.intValue()) continue;

            OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
            if (g == null) continue;

            BigDecimal iv = safeIv(g);
            if (iv == null) continue;

            if ("CE".equalsIgnoreCase(oi.getUnderlying_type())) ceIvs.add(iv);
            else if ("PE".equalsIgnoreCase(oi.getUnderlying_type())) peIvs.add(iv);
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
    // Utilities
    // =================================================================================

    /**
     * Round price to nearest step (e.g., 50 for NIFTY).
     */
    public BigDecimal computeAtmStrike(BigDecimal price, int step) {
        if (price == null) return null;
        int s = step <= 0 ? 50 : step;
        BigDecimal STEP = new BigDecimal(s);
        BigDecimal half = STEP.divide(new BigDecimal("2"), 0, RoundingMode.HALF_UP);
        BigDecimal mod = price.remainder(STEP);
        BigDecimal base = price.subtract(mod);
        return (mod.compareTo(half) >= 0) ? base.add(STEP) : base;
    }

    // =================================================================================
    // Internals (live reads + batching)
    // =================================================================================

    private List<OptionsInstruments.OptionInstrument> fetchInstruments(String underlyingKey, LocalDate expiry) {
        try {
            OptionsInstruments resp = upstox.getOptionInstrument(underlyingKey, expiry.toString());
            if (resp == null || resp.getData() == null) return Collections.emptyList();
            return resp.getData();
        } catch (Exception t) {
            log.error("fetchInstruments failed: {}", t);
            return Collections.emptyList();
        }
    }

    /**
     * Call greeks in batches to respect any CSV length limits.
     */
    private Map<String, OptionGreekResponse.OptionGreek> fetchGreeksMap(List<OptionsInstruments.OptionInstrument> instruments) {
        Map<String, OptionGreekResponse.OptionGreek> out = new HashMap<>();
        List<String> keys = new ArrayList<>(instruments.size());
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            String k = oi.getInstrument_key();
            if (k != null && !k.trim().isEmpty()) keys.add(k);
        }
        final int BATCH = 100; // adjust if API allows larger CSV
        for (int i = 0; i < keys.size(); i += BATCH) {
            String csv = joinCsv(keys, i, Math.min(i + BATCH, keys.size()));
            try {
                OptionGreekResponse g = upstox.getOptionGreeks(csv);
                if (g != null && g.getData() != null) out.putAll(g.getData());
            } catch (Exception t) {
                log.error("fetchGreeksMap batch failed: {}", t);
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

    // =================================================================================
    // Previously added helpers (kept)
    // =================================================================================

    public Optional<OptionGreekResponse.OptionGreek> getGreek(String instrumentKey) {
        try {
            Result<List<LocalDate>> expsRes = listNearestExpiries(Underlyings.NIFTY, 3);
            if (expsRes == null || !expsRes.isOk() || expsRes.get() == null) return Optional.empty();

            for (LocalDate exp : expsRes.get()) {
                Result<Map<String, OptionGreekResponse.OptionGreek>> opt =
                        getGreeksForExpiry(Underlyings.NIFTY, exp);
                if (opt.isOk()) {
                    OptionGreekResponse.OptionGreek g = opt.get().get(instrumentKey);
                    if (g != null) return Optional.of(g);
                }
            }
        } catch (Exception ignored) {
            log.error("getGreek failed: {}", ignored);
        }
        return Optional.empty();
    }

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

    private OiSnapshot computeOiSnapshot(String underlyingKey, LocalDate expiry) {
        try {
            List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
            if (instruments == null || instruments.isEmpty()) return null;

            Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
            if (greeks == null || greeks.isEmpty()) return null;

            long ceOi = 0L, peOi = 0L;
            for (OptionsInstruments.OptionInstrument oi : instruments) {
                OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
                if (g == null) continue;
                long val = Math.max(0, g.getOi());
                String side = oi.getUnderlying_type();
                if ("CE".equalsIgnoreCase(side)) ceOi += val;
                else if ("PE".equalsIgnoreCase(side)) peOi += val;
            }
            return new OiSnapshot(BigDecimal.valueOf(ceOi), BigDecimal.valueOf(peOi), Instant.now());
        } catch (Exception t) {
            log.error("computeOiSnapshot failed: {}", t);
            return null;
        }
    }

    /**
     * Rolling 3–4 snapshot buffer for total CE/PE OI.
     */
    public Optional<OiSnapshot> getLatestOiSnapshot(String underlyingKey, LocalDate expiry, int offset) {
        if (isBlank(underlyingKey) || expiry == null) return Optional.empty();
        final String key = underlyingKey + "|" + expiry;

        Deque<OiSnapshot> dq = oiSnapshotBuffer.computeIfAbsent(key, k -> new ArrayDeque<>(4));

        OiSnapshot now = computeOiSnapshot(underlyingKey, expiry);
        if (now != null) {
            OiSnapshot last = dq.peekLast();
            boolean changed = (last == null)
                    || last.totalCeOi.compareTo(now.totalCeOi) != 0
                    || last.totalPeOi.compareTo(now.totalPeOi) != 0;

            if (changed) {
                while (dq.size() >= 4) dq.pollFirst();
                dq.addLast(now);
            }
        }

        int idxFromEnd = (offset <= 0) ? Math.abs(offset) : offset;
        int i = 0;
        for (Iterator<OiSnapshot> it = dq.descendingIterator(); it.hasNext(); ) {
            OiSnapshot s = it.next();
            if (i == idxFromEnd) return Optional.of(s);
            i++;
        }
        if (!dq.isEmpty()) return Optional.ofNullable(dq.peekLast());
        return Optional.empty();
    }

    // =================================================================================
    // Private math helpers
    // =================================================================================

    private Map<Integer, Long> oiByStrike(List<OptionsInstruments.OptionInstrument> instruments,
                                          Map<String, OptionGreekResponse.OptionGreek> greeks,
                                          String side) {
        Map<Integer, Long> map = new HashMap<>();
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            if (!side.equalsIgnoreCase(oi.getUnderlying_type())) continue;
            OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
            if (g == null) continue;
            int strike = oi.getStrike_price();
            long val = Math.max(0, g.getOi());
            map.put(strike, map.getOrDefault(strike, 0L) + val);
        }
        return map;
    }

    private static BigDecimal mean(List<BigDecimal> vals) {
        if (vals == null || vals.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : vals) sum = sum.add(v);
        return sum.divide(new BigDecimal(vals.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * Safely read IV from greeks (supports BigDecimal/Number/String without reflection).
     * Returns null if IV not present/parsable.
     */
    private static BigDecimal safeIv(OptionGreekResponse.OptionGreek g) {
        try {
            Number v = g.getIv(); // your model exposes 'iv'; types may vary
            if (v == null) return null;
            if (v instanceof BigDecimal) return ((BigDecimal) v);
            if (v instanceof Number) return BigDecimal.valueOf(v.doubleValue());
            return new BigDecimal(v.toString());
        } catch (Exception ignore) {
            return null;
        }
    }

}
