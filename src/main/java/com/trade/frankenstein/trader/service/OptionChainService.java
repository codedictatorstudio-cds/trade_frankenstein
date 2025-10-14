package com.trade.frankenstein.trader.service;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.dto.OptionsFlowBias;
import com.trade.frankenstein.trader.enums.OptionType;
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

    private static long safeLong(Number n) {
        if (n == null) return 0L;
        try {
            return Math.max(0L, n.longValue());
        } catch (Exception e) {
            return 0L;
        }
    }

    // =================================================================================
    // Utilities
    // =================================================================================

    /**
     * List all contracts in a strike range (inclusive) for a given expiry.
     */
    public Result<List<InstrumentData>> listContractsByStrikeRange(
            String underlyingKey, LocalDate expiry, BigDecimal minStrike, BigDecimal maxStrike) {

        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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
                .collect(Collectors.<InstrumentData>toList());
        return Result.ok(filtered);
    }

    /**
     * Return up to {@code count} nearest expiries (>= today).
     * We probe upcoming Wed/Thu/Fri and keep the ones for which Upstox actually returns contracts.
     */
    public Result<List<LocalDate>> listNearestExpiries(String underlyingKey, int count) {
        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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
        Collections.sort(found);
        return Result.ok(found.subList(0, Math.min(found.size(), Math.max(1, count))));
    }

    /**
     * Find a single contract by expiry, strike and CALL/PUT.
     */
    public Result<InstrumentData> findContract(
            String underlyingKey, LocalDate expiry, BigDecimal strike, OptionType type) {

        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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
        // Enhanced validation
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.warn("Unauthorized OI PCR request for underlying: {}", underlyingKey);
            return Result.fail("UNAUTHORIZED", "User not authenticated");
        }

        if (isBlank(underlyingKey) || expiry == null) {
            log.warn("Invalid parameters for OI PCR: underlying={}, expiry={}", underlyingKey, expiry);
            return Result.fail("BAD_REQUEST", "underlyingKey and expiry are required");
        }

        try {
            // Fetch instruments with enhanced error handling
            List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
            if (instruments.isEmpty()) {
                log.info("No option instruments found for underlying: {} expiry: {}", underlyingKey, expiry);
                return Result.fail("NOT_FOUND", "No option instruments available for the specified expiry");
            }

            // Fetch Greeks with comprehensive validation
            Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
            if (greeks.isEmpty()) {
                log.warn("No Greeks data available for underlying: {} expiry: {}", underlyingKey, expiry);
                return Result.fail("NOT_FOUND", "Greeks data unavailable");
            }

            // Calculate OI with proper aggregation
            long ceOi = 0L, peOi = 0L;
            int processedInstruments = 0;

            for (InstrumentData instrument : instruments) {
                MarketQuoteOptionGreekV3 greek = greeks.get(instrument.getInstrumentKey());
                if (greek == null) continue;

                long oiValue = safeLong(greek.getOi());
                if (oiValue <= 0) continue; // Skip zero/negative OI

                if (sideEquals(instrument, "CE")) {
                    ceOi += oiValue;
                } else if (sideEquals(instrument, "PE")) {
                    peOi += oiValue;
                }
                processedInstruments++;
            }

            // Enhanced validation and logging
            if (ceOi == 0L) {
                log.warn("Zero Call OI for underlying: {} expiry: {}", underlyingKey, expiry);
                return Result.fail("INVALID_DATA", "Call option open interest is zero");
            }

            if (peOi == 0L) {
                log.warn("Zero Put OI for underlying: {} expiry: {}", underlyingKey, expiry);
            }

            BigDecimal pcrRatio = BigDecimal.valueOf(peOi)
                    .divide(BigDecimal.valueOf(ceOi), 6, RoundingMode.HALF_UP);

            log.debug("OI PCR calculated for {}: CE_OI={}, PE_OI={}, PCR={}, processed_instruments={}",
                    underlyingKey, ceOi, peOi, pcrRatio, processedInstruments);

            // Audit the calculation
            audit("oi.pcr.calculated", createAuditData(
                    "underlying", underlyingKey,
                    "expiry", expiry.toString(),
                    "ceOi", String.valueOf(ceOi),
                    "peOi", String.valueOf(peOi),
                    "pcr", pcrRatio.toString(),
                    "instruments", String.valueOf(processedInstruments)
            ));

            return Result.ok(pcrRatio);

        } catch (Exception ex) {
            log.error("Failed to calculate OI PCR for underlying: {} expiry: {}",
                    underlyingKey, expiry, ex);
            return Result.fail("INTERNAL_ERROR", "Failed to calculate OI PCR: " + ex.getMessage());
        }
    }

    /**
     * Volume Put/Call ratio (PE Vol / CE Vol) for an expiry, from live greeks volume.
     */
    public Result<BigDecimal> getVolumePcr(String underlyingKey, LocalDate expiry) {
        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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

        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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
            long before = (prev == null) ? 0L : (prev.getOrDefault(e.getKey(), 0L));
            long d = e.getValue() - before;
            if (d > 0L) deltas.put(e.getKey(), d);
        }

        // update cache
        cache.put(key, curr);

        // sort desc by delta, limit, and return
        List<Map.Entry<Integer, Long>> entries = new ArrayList<>(deltas.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        LinkedHashMap<Integer, Long> out = new LinkedHashMap<>();
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

        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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

        if (isNotLoggedIn()) return Result.fail("user-not-logged-in");
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

    /**
     * Latest OI snapshot (offset 0 = now, -1 = previous, etc.).
     * Uses Redis key tf:oi:{underlyingKey}:{yyyy-MM-dd} with 10s TTL for the "now" snapshot.
     * When offset != 0 we recompute live and do not cache historical points.
     */
    public Optional<OiSnapshot> getLatestOiSnapshot(String underlyingKey, LocalDate expiry, int offset) {
        if (isNotLoggedIn()) return Optional.empty();
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
        if (isBlank(underlyingKey) || expiry == null) {
            log.warn("Invalid parameters for OI snapshot computation");
            return null;
        }

        Instant computationStart = Instant.now();

        try {
            // Fetch instruments with validation
            List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
            if (instruments.isEmpty()) {
                log.debug("No instruments available for OI snapshot: {} {}", underlyingKey, expiry);
                return null;
            }

            // Fetch Greeks data
            Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);
            if (greeks.isEmpty()) {
                log.debug("No Greeks data for OI snapshot: {} {}", underlyingKey, expiry);
                return null;
            }

            // Calculate aggregated OI with detailed tracking
            long totalCeOi = 0L, totalPeOi = 0L;
            int ceContracts = 0, peContracts = 0;
            int skippedContracts = 0;

            for (InstrumentData instrument : instruments) {
                MarketQuoteOptionGreekV3 greek = greeks.get(instrument.getInstrumentKey());
                if (greek == null) {
                    skippedContracts++;
                    continue;
                }

                long oiValue = safeLong(greek.getOi());
                if (oiValue <= 0) {
                    skippedContracts++;
                    continue;
                }

                if (sideEquals(instrument, "CE")) {
                    totalCeOi += oiValue;
                    ceContracts++;
                } else if (sideEquals(instrument, "PE")) {
                    totalPeOi += oiValue;
                    peContracts++;
                } else {
                    log.debug("Unknown option type for instrument: {}", instrument.getInstrumentKey());
                    skippedContracts++;
                }
            }

            Instant snapshotTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            long computationDuration = ChronoUnit.MILLIS.between(computationStart, snapshotTime);

            log.debug("OI snapshot computed for {}: CE_OI={} ({}contracts), PE_OI={} ({}contracts), " +
                            "skipped={}, duration={}ms",
                    underlyingKey, totalCeOi, ceContracts, totalPeOi, peContracts,
                    skippedContracts, computationDuration);

            // Create snapshot with enhanced metadata
            OiSnapshot snapshot = new OiSnapshot(
                    BigDecimal.valueOf(totalCeOi),
                    BigDecimal.valueOf(totalPeOi),
                    snapshotTime
            );

            // Publish snapshot event for real-time consumers
            if (eventPublisher != null) {
                publishOiSnapshotEvent(underlyingKey, expiry, snapshot,
                        ceContracts, peContracts, computationDuration);
            }

            return snapshot;

        } catch (Exception ex) {
            log.error("Failed to compute OI snapshot for underlying: {} expiry: {}",
                    underlyingKey, expiry, ex);

            // Audit the failure
            audit("oi.snapshot.failed", createAuditData(
                    "underlying", underlyingKey,
                    "expiry", expiry.toString(),
                    "error", ex.getMessage()
            ));

            return null;
        }
    }

    private void publishOiSnapshotEvent(String underlyingKey, LocalDate expiry, OiSnapshot snapshot,
                                        int ceContracts, int peContracts, long computationDuration) {
        try {
            JsonObject event = new JsonObject();
            event.addProperty("type", "oi.snapshot");
            event.addProperty("underlying", underlyingKey);
            event.addProperty("expiry", expiry.toString());
            event.addProperty("ceOi", snapshot.totalCeOi().longValue());
            event.addProperty("peOi", snapshot.totalPeOi().longValue());
            event.addProperty("ceContracts", ceContracts);
            event.addProperty("peContracts", peContracts);
            event.addProperty("computationMs", computationDuration);
            event.addProperty("timestamp", snapshot.asOf().toEpochMilli());

            eventPublisher.publish(EventBusConfig.TOPIC_OPTION_CHAIN, underlyingKey, event.toString());
        } catch (Exception ex) {
            log.warn("Failed to publish OI snapshot event", ex);
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.warn("Attempted to fetch instruments without authentication for underlying: {}", underlyingKey);
            return Collections.emptyList();
        }

        try {
            GetOptionContractResponse resp = upstox.getOptionInstrument(underlyingKey, expiry.toString());
            if (resp == null || resp.getData() == null) {
                log.debug("No instrument data returned for underlying: {} expiry: {}", underlyingKey, expiry);
                return Collections.emptyList();
            }

            List<InstrumentData> instruments = resp.getData();
            log.debug("Retrieved {} instruments for underlying: {} expiry: {}",
                    instruments.size(), underlyingKey, expiry);

            // Audit successful fetch
            audit("instruments.fetched", createAuditData("underlying", underlyingKey,
                    "expiry", expiry.toString(),
                    "count", String.valueOf(instruments.size())));
            return instruments;

        } catch (Exception ex) {
            log.error("Failed to fetch instruments for underlying: {} expiry: {}",
                    underlyingKey, expiry, ex);
            return Collections.emptyList();
        }
    }

    /**
     * Call greeks in batches to respect CSV limits on the Upstox endpoint.
     */
    private Map<String, MarketQuoteOptionGreekV3> fetchGreeksMap(List<InstrumentData> instruments) {
        Map<String, MarketQuoteOptionGreekV3> greeksMap = new ConcurrentHashMap<>();

        if (instruments == null || instruments.isEmpty()) {
            log.debug("No instruments provided for Greeks fetch");
            return greeksMap;
        }

        List<String> validKeys = instruments.stream()
                .map(InstrumentData::getInstrumentKey)
                .filter(key -> key != null && !key.trim().isEmpty())
                .toList();

        if (validKeys.isEmpty()) {
            log.warn("No valid instrument keys found from {} instruments", instruments.size());
            return greeksMap;
        }

        final int batchSize = 100; // Could be externalized to configuration
        int totalBatches = (validKeys.size() + batchSize - 1) / batchSize;
        log.debug("Fetching Greeks for {} instruments in {} batches", validKeys.size(), totalBatches);

        for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
            int start = batchIndex * batchSize;
            int end = Math.min(start + batchSize, validKeys.size());

            String csv = String.join(",", validKeys.subList(start, end));

            try {
                GetMarketQuoteOptionGreekResponseV3 response = upstox.getOptionGreeks(csv);
                if (response != null && response.getData() != null) {
                    Map<String, MarketQuoteOptionGreekV3> batchData = response.getData();
                    greeksMap.putAll(batchData);
                    log.debug("Successfully fetched Greeks for batch {}/{}: {} instruments",
                            batchIndex + 1, totalBatches, batchData.size());
                } else {
                    log.warn("Empty Greeks response for batch {}/{}", batchIndex + 1, totalBatches);
                }
            } catch (Exception ex) {
                log.error("Failed to fetch Greeks for batch {}/{} with {} keys",
                        batchIndex + 1, totalBatches, end - start, ex);

                // Continue with other batches rather than failing completely
            }
        }

        log.debug("Total Greeks fetched: {}/{} instruments", greeksMap.size(), validKeys.size());
        return greeksMap;
    }


    // =================================================================================
    // Hedge sizing helpers — pick closest |delta| strikes (Java 8, SDK-safe)
    // =================================================================================

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
            map.compute(k, (key, prev) -> (prev == null ? 0L : prev) + add);
        }
        return map;
    }

    public Optional<MarketQuoteOptionGreekV3> getGreek(String instrumentKey) {
        if (isNotLoggedIn()) return Optional.empty();
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

    // =================================================================================
    // Auth guard
    // =================================================================================
    private boolean isNotLoggedIn() {
        try {
            return !AuthCodeHolder.getInstance().isLoggedIn(); // ✓ Remove negation
        } catch (Throwable t) {
            return false; // ✓ Return false on auth error
        }
    }

    /**
     * Returns a snapshot of options flow (CE/PE volumes, OI change) for the instrument and expiry.
     */
    public Optional<OptionsFlowBias> analyzeOptionsFlow(String underlyingKey, LocalDate expiry) {
        if (isNotLoggedIn() || underlyingKey == null || expiry == null) return Optional.empty();
        try {
            // CE/PE volumes and OI from current expiry greeks data
            List<InstrumentData> instruments = fetchInstruments(underlyingKey, expiry);
            Map<String, MarketQuoteOptionGreekV3> greeks = fetchGreeksMap(instruments);

            double ceVolume = 0, peVolume = 0;
            double ceOiChange = 0, peOiChange = 0;

            for (InstrumentData oi : instruments) {
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

    // ===== Kafkaesque audit helper (optional; uses same EventPublisher) =====
    private void audit(String event, JsonObject data) {
        try {
            if (this.eventPublisher == null) return;
            java.time.Instant now = java.time.Instant.now();
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", event);
            o.addProperty("source", "option_chain");
            if (data != null) o.add("data", data);
            this.eventPublisher.publish(EventBusConfig.TOPIC_AUDIT, event, o.toString());
        } catch (Throwable ignore) { /* best-effort */ }
    }


    private JsonObject createAuditData(String... keyValuePairs) {
        JsonObject data = new JsonObject();
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            data.addProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return data;
    }
}
