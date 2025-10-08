package com.trade.frankenstein.trader.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.dto.OptionsFlowBias;
import com.upstox.api.GetMarketQuoteOptionGreekResponseV3;
import com.upstox.api.GetOptionContractResponse;
import com.upstox.api.InstrumentData;
import com.upstox.api.MarketQuoteOptionGreekV3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
public class OptionChainService {

    private final Map<String, Map<Integer, Long>> lastCeOiByStrike = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> lastPeOiByStrike = new ConcurrentHashMap<>();

    @Autowired
    private UpstoxService upstox;
    @Autowired
    private FastStateStore fast;
    @Autowired
    private EventPublisher eventPublisher;

    // Track last delta-pick per symbol+expiry for audit diffs
    private final ConcurrentHashMap<String, DeltaPick> lastPickByKey = new ConcurrentHashMap<>();


    private static String joinCsv(List<String> keys, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(',');
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    // =================================================================================
    // Real-time metrics (PCR, Max Pain, Greeks snapshot)
    // =================================================================================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String optTypeCode(OptionType t) {
        if (t == OptionType.CALL) return "CE";
        if (t == OptionType.PUT) return "PE";
        return "";
    }

    private static String chainKey(String underlyingKey, LocalDate expiry) {
        return underlyingKey + "|" + expiry;
    }

    // =================================================================================
    // OI Δ, IV Percentile, IV Skew (SDK-safe)
    // =================================================================================

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

    // =================================================================================
    // Fast snapshot (Step-3): total CE/PE OI per expiry with Redis TTL=10s
    // =================================================================================

    private static double safeDouble(Number n) {
        if (n == null) return Double.NaN;
        try {
            return n.doubleValue();
        } catch (Exception e) {
            return Double.NaN;
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

    // Approximate ATM strike for tie-breaker
    private static int strikeIntApprox(List<InstrumentData> instruments) {
        // Fallback: median strike among instruments (quick & stable)
        if (instruments == null || instruments.isEmpty()) return 0;
        int n = instruments.size();
        int[] arr = new int[n];
        int i = 0;
        for (InstrumentData oi : instruments) arr[i++] = strikeInt(oi);
        Arrays.sort(arr);
        return arr[n / 2];
    }

    // =================================================================================
    // Utilities
    // =================================================================================

    /**
     * List all contracts in a strike range (inclusive) for a given expiry.
     */
    public Result<List<InstrumentData>> listContractsByStrikeRange(
            String underlyingKey, LocalDate expiry, BigDecimal minStrike, BigDecimal maxStrike) {

        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (isBlank(underlyingKey) || expiry == null || minStrike == null || maxStrike == null) {
            return Result.fail("BAD_REQUEST", "underlyingKey, expiry, minStrike, maxStrike are required");
        }
        List<InstrumentData> all = fetchInstruments(underlyingKey, expiry);
        if (all.isEmpty()) return Result.ok(Collections.emptyList());

        final int minK = minStrike.setScale(0, RoundingMode.HALF_UP).intValue();
        final int maxK = maxStrike.setScale(0, RoundingMode.HALF_UP).intValue();

        List<InstrumentData> filtered = all.stream()
                .filter(c -> strikeInt(c) >= minK && strikeInt(c) <= maxK)
                .sorted(new Comparator<InstrumentData>() {
                    @Override
                    public int compare(InstrumentData a, InstrumentData b) {
                        return Integer.compare(strikeInt(a), strikeInt(b));
                    }
                })
                .collect(Collectors.<InstrumentData>toList());
        return Result.ok(filtered);
    }

    /**
     * Return up to {@code count} nearest expiries (>= today).
     * We probe upcoming Wed/Thu/Fri and keep the ones for which Upstox actually returns contracts.
     */
    public Result<List<LocalDate>> listNearestExpiries(String underlyingKey, int count) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (isBlank(underlyingKey)) return Result.fail("BAD_REQUEST", "underlyingKey required");

        final LocalDate today = LocalDate.now();
        final LocalDate horizon = today.plusDays(56);
        Set<LocalDate> candidates = new LinkedHashSet<LocalDate>();
        for (LocalDate d = today; !d.isAfter(horizon); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY || dow == DayOfWeek.FRIDAY) {
                candidates.add(d);
            }
        }

        List<LocalDate> found = new ArrayList<LocalDate>();
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
        Collections.sort(found);
        return Result.ok(found.subList(0, Math.min(found.size(), Math.max(1, count))));
    }

    /**
     * Find a single contract by expiry, strike and CALL/PUT.
     */
    public Result<InstrumentData> findContract(
            String underlyingKey, LocalDate expiry, BigDecimal strike, OptionType type) {

        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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

    /**
     * OI Put/Call ratio (PE OI / CE OI) for an expiry, from live greeks.
     */
    public Result<BigDecimal> getOiPcr(String underlyingKey, LocalDate expiry) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");
        return Result.ok(greeks);
    }

    /**
     * Top OI increases by strike for a given expiry and option side.
     * Returns LinkedHashMap<strike, deltaOi> sorted by descending delta, limited to {@code limit}.
     */
    public Result<LinkedHashMap<Integer, Long>> topOiChange(
            String underlyingKey, LocalDate expiry, OptionType type, int limit) {

        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        Map<Integer, Long> deltas = new HashMap<Integer, Long>();
        for (Map.Entry<Integer, Long> e : curr.entrySet()) {
            long before = (prev == null) ? 0L : (prev.containsKey(e.getKey()) ? prev.get(e.getKey()) : 0L);
            long d = e.getValue() - before;
            if (d > 0L) deltas.put(e.getKey(), d);
        }

        // update cache
        cache.put(key, curr);

        // sort desc by delta, limit, and return
        List<Map.Entry<Integer, Long>> entries = new ArrayList<Map.Entry<Integer, Long>>(deltas.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<Integer, Long>>() {
            @Override
            public int compare(Map.Entry<Integer, Long> a, Map.Entry<Integer, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        LinkedHashMap<Integer, Long> out = new LinkedHashMap<Integer, Long>();
        int max = Math.max(1, limit);
        for (int i = 0; i < entries.size() && i < max; i++) {
            Map.Entry<Integer, Long> e = entries.get(i);
            out.put(e.getKey(), e.getValue());
        }

        if (out.isEmpty()) return Result.fail("NO_CHANGE", "No positive OI increases");
        return Result.ok(out);
    }

    /**
     * IV percentile (0..100) for the given strike/type within the same expiry + side population.
     * Example: 90 means the strike's IV is higher than 90% of same-side strikes for that expiry.
     */
    public Result<BigDecimal> getIvPercentile(
            String underlyingKey, LocalDate expiry, BigDecimal strike, OptionType type) {

        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        List<BigDecimal> ivs = new ArrayList<BigDecimal>();
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

        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        List<BigDecimal> ceIvs = new ArrayList<BigDecimal>();
        List<BigDecimal> peIvs = new ArrayList<BigDecimal>();
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

    /**
     * Latest OI snapshot (offset 0 = now, -1 = previous, etc.).
     * Uses Redis key tf:oi:{underlyingKey}:{yyyy-MM-dd} with 10s TTL for the "now" snapshot.
     * When offset != 0 we recompute live and do not cache historical points.
     */
    public Optional<OiSnapshot> getLatestOiSnapshot(String underlyingKey, LocalDate expiry, int offset) {
        if (!isLoggedIn()) return Optional.empty();
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
        Map<String, MarketQuoteOptionGreekV3> out = new HashMap<String, MarketQuoteOptionGreekV3>();
        if (instruments == null || instruments.isEmpty()) return out;

        List<String> keys = new ArrayList<String>(instruments.size());
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

    // =================================================================================
    // Hedge sizing helpers — pick closest |delta| strikes (Java 8, SDK-safe)
    // =================================================================================

    private Map<Integer, Long> oiByStrike(List<InstrumentData> instruments,
                                          Map<String, MarketQuoteOptionGreekV3> greeks,
                                          String side) {
        Map<Integer, Long> map = new HashMap<Integer, Long>();
        for (InstrumentData oi : instruments) {
            if (!sideEquals(oi, side)) continue;
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;
            int k = strikeInt(oi);
            long add = safeLong(g.getOi());
            Long prev = map.get(k);
            map.put(k, (prev == null ? 0L : prev) + add);
        }
        return map;
    }

    public Optional<MarketQuoteOptionGreekV3> getGreek(String instrumentKey) {
        if (!isLoggedIn()) return Optional.empty();
        try {
            Result<List<LocalDate>> expsRes = listNearestExpiries(Underlyings.NIFTY, 3);
            if (expsRes == null || !expsRes.isOk() || expsRes.get() == null) return Optional.empty();

            for (LocalDate exp : expsRes.get()) {
                Result<Map<String, MarketQuoteOptionGreekV3>> opt =
                        getGreeksForExpiry(Underlyings.NIFTY, exp);
                if (opt.isOk() && opt.get() != null) {
                    MarketQuoteOptionGreekV3 g = opt.get().get(instrumentKey);
                    if (g != null) return Optional.of(g);
                }
            }
        } catch (Exception ignored) {
            log.error("getGreek failed: {}", ignored);
        }
        return Optional.empty();
    }

    /**
     * Pick the closest-|delta| option contracts for a given side (CALL/PUT).
     * Uses live greeks from Upstox; skips contracts without a delta.
     *
     * @param underlyingKey  e.g., "NSE_INDEX|Nifty 50"
     * @param expiry         expiry date
     * @param type           CALL or PUT
     * @param targetAbsDelta target absolute delta in 0..1 (e.g., 0.20). If null, defaults to 0.20.
     * @param count          how many contracts to return (>=1)
     */
    public Result<List<InstrumentData>> pickClosestDeltaStrikes(String underlyingKey,
                                                                LocalDate expiry,
                                                                OptionType type,
                                                                BigDecimal targetAbsDelta,
                                                                int count) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (underlyingKey == null || underlyingKey.trim().isEmpty() || expiry == null || type == null) {
            return Result.fail("BAD_REQUEST", "params required");
        }

        List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        // Build greeks map once
        Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
        if (greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        String side = optTypeCode(type); // "CE" / "PE"
        double tgt = targetAbsDelta == null ? 0.20 : Math.max(0.0, Math.min(1.0, targetAbsDelta.doubleValue()));
        final List<Score> scored = new ArrayList<>();

        for (InstrumentData oi : instruments) {
            if (!sideEquals(oi, side)) continue;
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null) continue;
            double d = safeDouble(g.getDelta());
            if (Double.isNaN(d)) continue;
            double ad = Math.abs(d);
            double err = Math.abs(ad - tgt);
            scored.add(new Score(oi, ad, err));
        }

        if (scored.isEmpty()) return Result.fail("NOT_FOUND", "No contracts with delta");

        // Sort by smallest |delta - target|, then by |delta| descending (tie-breaker), then by strike proximity to ATM
        Collections.sort(scored, new Comparator<Score>() {
            public int compare(Score a, Score b) {
                int cmp = Double.compare(a.err, b.err);
                if (cmp != 0) return cmp;
                // Prefer higher absolute delta (more hedge effectiveness) when equally close
                cmp = Double.compare(b.absDelta, a.absDelta);
                if (cmp != 0) return cmp;
                // Final tie-breaker: closer strike to ATM
                int atm = strikeIntApprox(instruments);
                return Integer.compare(Math.abs(strikeInt(a.oi) - atm), Math.abs(strikeInt(b.oi) - atm));
            }
        });

        int n = Math.max(1, count);
        List<InstrumentData> out = new ArrayList<InstrumentData>(n);
        for (int i = 0; i < scored.size() && i < n; i++) {
            out.add(scored.get(i).oi);
        }
        return Result.ok(out);
    }

    /**
     * Convenience helper: pick both CE and PE hedge legs around the same target |delta|.
     * Returns a map with keys OptionType.CALL and OptionType.PUT.
     */
    public Result<Map<OptionType, List<InstrumentData>>> pickClosestDeltaPair(String underlyingKey,
                                                                              LocalDate expiry,
                                                                              BigDecimal targetAbsDelta,
                                                                              int countPerSide) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (underlyingKey == null || underlyingKey.trim().isEmpty() || expiry == null) {
            return Result.fail("BAD_REQUEST", "params required");
        }
        Map<OptionType, List<InstrumentData>> map = new EnumMap<OptionType, List<InstrumentData>>(OptionType.class);

        Result<List<InstrumentData>> ce = pickClosestDeltaStrikes(underlyingKey, expiry, OptionType.CALL, targetAbsDelta, countPerSide);
        if (!ce.isOk()) return Result.fail(ce.getErrorCode(), ce.getError());
        map.put(OptionType.CALL, ce.get());

        Result<List<InstrumentData>> pe = pickClosestDeltaStrikes(underlyingKey, expiry, OptionType.PUT, targetAbsDelta, countPerSide);
        if (!pe.isOk()) return Result.fail(pe.getErrorCode(), pe.getError());
        map.put(OptionType.PUT, pe.get());

        return Result.ok(map);
    }

    // =================================================================================
    // Auth guard
    // =================================================================================
    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== Step-10 additions: helpers (closest-|Δ|, JSON, publish) =====
    public DeltaPick pickClosestDeltaStrikesFromRows(List<BasicOptionRow> rows, double targetAbsDelta) {
        if (rows == null || rows.isEmpty()) {
            return new DeltaPick(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        BasicOptionRow bestCe = null;
        BasicOptionRow bestPe = null;
        double ceGap = Double.MAX_VALUE;
        double peGap = Double.MAX_VALUE;
        for (BasicOptionRow r : rows) {
            if (r == null || Double.isNaN(r.delta)) continue;
            final double gap = Math.abs(Math.abs(r.delta) - targetAbsDelta);
            if ("CE".equalsIgnoreCase(r.type)) {
                if (gap < ceGap) {
                    ceGap = gap;
                    bestCe = r;
                }
            } else if ("PE".equalsIgnoreCase(r.type)) {
                if (gap < peGap) {
                    peGap = gap;
                    bestPe = r;
                }
            }
        }
        final double ceStrike = bestCe != null ? bestCe.strike : Double.NaN;
        final double peStrike = bestPe != null ? bestPe.strike : Double.NaN;
        final double ceDelta = bestCe != null ? bestCe.delta : Double.NaN;
        final double peDelta = bestPe != null ? bestPe.delta : Double.NaN;
        return new DeltaPick(ceStrike, peStrike, ceDelta, peDelta);
    }

    public String buildDeltaPickJsonString(String symbol,
                                           String expiry,
                                           double targetAbsDelta,
                                           DeltaPick pick,
                                           String rationale) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("type", "delta_pick");
        payload.addProperty("symbol", nullSafe(symbol));
        payload.addProperty("expiry", nullSafe(expiry));
        payload.addProperty("targetAbsDelta", targetAbsDelta);
        payload.addProperty("ts", Instant.now().toEpochMilli());
        payload.addProperty("rationale", nullSafe(rationale));

        final JsonObject ce = new JsonObject();
        ce.addProperty("strike", pick != null ? pick.ceStrike() : Double.NaN);
        ce.addProperty("delta", pick != null ? pick.ceDelta() : Double.NaN);

        final JsonObject pe = new JsonObject();
        pe.addProperty("strike", pick != null ? pick.peStrike() : Double.NaN);
        pe.addProperty("delta", pick != null ? pick.peDelta() : Double.NaN);

        payload.add("ce", ce);
        payload.add("pe", pe);
        return payload.toString();
    }

    public void publishDeltaPick(String symbol,
                                 String expiry,
                                 double targetAbsDelta,
                                 DeltaPick pick,
                                 String rationale) {
        try {
            final String lpKey = key(symbol, expiry);
            final DeltaPick prev = lastPickByKey.get(lpKey);
            final DeltaPick cur = pick;
            boolean changed = false;
            if (prev == null && cur != null) changed = true;
            else if (prev != null && cur != null) {
                // significant change if strike changed OR |delta diff| > 0.05 on either leg
                boolean strikeChanged = (Double.doubleToLongBits(prev.ceStrike()) != Double.doubleToLongBits(cur.ceStrike()))
                        || (Double.doubleToLongBits(prev.peStrike()) != Double.doubleToLongBits(cur.peStrike()));
                double dCe = Math.abs((Double.isNaN(prev.ceDelta()) ? 0 : prev.ceDelta()) - (Double.isNaN(cur.ceDelta()) ? 0 : cur.ceDelta()));
                double dPe = Math.abs((Double.isNaN(prev.peDelta()) ? 0 : prev.peDelta()) - (Double.isNaN(cur.peDelta()) ? 0 : cur.peDelta()));
                boolean deltaMoved = (dCe > 0.05d) || (dPe > 0.05d);
                changed = strikeChanged || deltaMoved;
            }

            if (this.eventPublisher == null) return;
            final String key = key(symbol, expiry);
            final String json = buildDeltaPickJsonString(symbol, expiry, targetAbsDelta, pick, rationale);
            lastPickByKey.put(lpKey, cur);
            if (changed) {
                com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                d.addProperty("symbol", symbol);
                d.addProperty("expiry", expiry);
                d.addProperty("targetAbsDelta", targetAbsDelta);
                if (cur != null) {
                    com.google.gson.JsonObject ce = new com.google.gson.JsonObject();
                    ce.addProperty("strike", cur.ceStrike());
                    ce.addProperty("delta", cur.ceDelta());
                    com.google.gson.JsonObject pe = new com.google.gson.JsonObject();
                    pe.addProperty("strike", cur.peStrike());
                    pe.addProperty("delta", cur.peDelta());
                    d.add("ce", ce);
                    d.add("pe", pe);
                }
                if (rationale != null) d.addProperty("rationale", rationale);
                audit("option_chain.pick_changed", d);
            }
        } catch (Throwable t) {
            // non-fatal to keep existing behavior
        }
    }

    public String buildOptionChainLightJsonString(String symbol,
                                                  String expiry,
                                                  Collection<BasicOptionRow> rows) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("type", "chain_light");
        payload.addProperty("symbol", nullSafe(symbol));
        payload.addProperty("expiry", nullSafe(expiry));
        payload.addProperty("ts", Instant.now().toEpochMilli());

        final JsonArray arr = new JsonArray();
        if (rows != null) {
            for (BasicOptionRow r : rows) {
                if (r == null) continue;
                arr.add(toJson(r));
            }
        }
        payload.add("rows", arr);
        return payload.toString();
    }

    public void publishOptionChainLight(String symbol, String expiry, Collection<BasicOptionRow> rows) {
        try {
            if (this.eventPublisher == null) return;
            final String key = key(symbol, expiry);
            final String json = buildOptionChainLightJsonString(symbol, expiry, rows);
            this.eventPublisher.publish(EventBusConfig.TOPIC_OPTION_CHAIN, key, json);
        } catch (Throwable t) {
            // non-fatal
        }
    }

    private JsonObject toJson(BasicOptionRow r) {
        final JsonObject o = new JsonObject();
        o.addProperty("strike", r.strike);
        o.addProperty("type", r.type);
        if (!Double.isNaN(r.ltp)) o.addProperty("ltp", r.ltp);
        if (!Double.isNaN(r.iv)) o.addProperty("iv", r.iv);
        if (!Double.isNaN(r.oi)) o.addProperty("oi", r.oi);
        if (!Double.isNaN(r.delta)) o.addProperty("delta", r.delta);
        if (!Double.isNaN(r.gamma)) o.addProperty("gamma", r.gamma);
        if (!Double.isNaN(r.theta)) o.addProperty("theta", r.theta);
        if (!Double.isNaN(r.vega)) o.addProperty("vega", r.vega);
        if (r.instrumentKey != null) o.addProperty("instrumentKey", r.instrumentKey);
        return o;
    }

    private String key(String symbol, String expiry) {
        return nullSafe(symbol) + "|" + nullSafe(expiry);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns a snapshot of options flow (CE/PE volumes, OI change) for the instrument and expiry.
     */
    public Optional<OptionsFlowBias> analyzeOptionsFlow(String underlyingKey, LocalDate expiry) {
        if (!isLoggedIn() || underlyingKey == null || expiry == null) return Optional.empty();
        try {
            // CE/PE volumes and OI from current expiry greeks data
            List instruments = fetchInstruments(underlyingKey, expiry);
            Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);

            double ceVolume = 0, peVolume = 0;
            double ceOiChange = 0, peOiChange = 0;

            for (Object oiObj : instruments) {
                InstrumentData oi = (InstrumentData) oiObj;
                MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
                if (g == null) continue;
                long vol = safeLong(g.getVolume());
                long oiVal = safeLong(g.getOi());
                long oiPrev = 0;
                String side = oi.getUnderlyingType();
                if ("CE".equalsIgnoreCase(side)) {
                    ceVolume += vol;
                    ceOiChange += oiVal - oiPrev;
                } else if ("PE".equalsIgnoreCase(side)) {
                    peVolume += vol;
                    peOiChange += oiVal - oiPrev;
                }
            }

            double totalVol = ceVolume + peVolume;
            double callVolRatio = totalVol > 0 ? ceVolume / totalVol : 0.5;
            double putVolRatio = totalVol > 0 ? peVolume / totalVol : 0.5;
            double netOiChange = ceOiChange - peOiChange;

            OptionsFlowBias flow = new OptionsFlowBias(callVolRatio, putVolRatio, netOiChange, Instant.now());
            return Optional.of(flow);

        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public record OiSnapshot(BigDecimal totalCeOi, BigDecimal totalPeOi, Instant asOf) {
    }

    // Local score struct (Java 8-friendly, no Lombok needed here)
    private record Score(InstrumentData oi, double absDelta, double err) {
    }

    // ===== Step-10 additions: lightweight DTOs (kept inside service to avoid coupling) =====
    public static final class BasicOptionRow {
        public final double strike;
        public final String type; // "CE" or "PE"
        public final double ltp;   // optional
        public final double iv;    // optional
        public final double oi;    // optional
        public final double delta; // optional
        public final double gamma; // optional
        public final double theta; // optional
        public final double vega;  // optional
        public final String instrumentKey; // optional

        private BasicOptionRow(Builder b) {
            this.strike = b.strike;
            this.type = b.type;
            this.ltp = b.ltp;
            this.iv = b.iv;
            this.oi = b.oi;
            this.delta = b.delta;
            this.gamma = b.gamma;
            this.theta = b.theta;
            this.vega = b.vega;
            this.instrumentKey = b.instrumentKey;
        }

        public static Builder builder(double strike, String type) {
            return new Builder(strike, type);
        }

        public static final class Builder {
            private final double strike;
            private final String type;
            private double ltp = Double.NaN;
            private double iv = Double.NaN;
            private double oi = Double.NaN;
            private double delta = Double.NaN;
            private double gamma = Double.NaN;
            private double theta = Double.NaN;
            private double vega = Double.NaN;
            private String instrumentKey;

            public Builder(double strike, String type) {
                this.strike = strike;
                this.type = type;
            }

            public Builder ltp(double v) {
                this.ltp = v;
                return this;
            }

            public Builder iv(double v) {
                this.iv = v;
                return this;
            }

            public Builder oi(double v) {
                this.oi = v;
                return this;
            }

            public Builder delta(double v) {
                this.delta = v;
                return this;
            }

            public Builder gamma(double v) {
                this.gamma = v;
                return this;
            }

            public Builder theta(double v) {
                this.theta = v;
                return this;
            }

            public Builder vega(double v) {
                this.vega = v;
                return this;
            }

            public Builder instrumentKey(String v) {
                this.instrumentKey = v;
                return this;
            }

            public BasicOptionRow build() {
                return new BasicOptionRow(this);
            }
        }
    }

    public record DeltaPick(double ceStrike, double peStrike, double ceDelta, double peDelta) {

        @Override
        public String toString() {
            return "DeltaPick{ceStrike=" + ceStrike + ", peStrike=" + peStrike +
                    ", ceDelta=" + ceDelta + ", peDelta=" + peDelta + "}";
        }
    }


    // ===== Kafkaesque audit helper (optional; uses same EventPublisher) =====
    private void audit(String event, com.google.gson.JsonObject data) {
        try {
            if (this.eventPublisher == null) return;
            java.time.Instant now = java.time.Instant.now();
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", event);
            o.addProperty("source", "option_chain");
            if (data != null) o.add("data", data);
            this.eventPublisher.publish(EventBusConfig.TOPIC_AUDIT, "option_chain", o.toString());
        } catch (Throwable ignore) { /* best-effort */ }
    }


    /**
     * Publish a compact greeks snapshot for the current chain.
     * Emits to TOPIC_OPTION_CHAIN with event="option_chain.greeks_snapshot".
     * Backward compatible: does not alter existing publishers.
     */
    public void publishGreeksSnapshot(String symbol, String expiry, java.util.Collection<BasicOptionRow> rows) {
        try {
            if (this.eventPublisher == null) return;
            final String k = key(symbol, expiry);
            java.time.Instant now = java.time.Instant.now();
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("type", "greeks_snapshot");
            payload.addProperty("event", "option_chain.greeks_snapshot");
            payload.addProperty("source", "option_chain");
            payload.addProperty("symbol", nullSafe(symbol));
            payload.addProperty("expiry", nullSafe(expiry));
            payload.addProperty("ts", now.toEpochMilli());
            payload.addProperty("ts_iso", now.toString());
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            if (rows != null) {
                for (BasicOptionRow r : rows) {
                    if (r == null) continue;
                    arr.add(toJson(r));
                }
            }
            payload.add("rows", arr);
            this.eventPublisher.publish(EventBusConfig.TOPIC_OPTION_CHAIN, k, payload.toString());
        } catch (Throwable t) {
            // non-fatal
        }
    }

}
