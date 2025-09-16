package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.model.upstox.OptionGreekResponse;
import com.trade.frankenstein.trader.model.upstox.OptionsInstruments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptionChainService {

    private final UpstoxService upstox;

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
        OptionsInstruments resp = upstox.getOptionInstrument(underlyingKey, null);
        List<OptionsInstruments.OptionInstrument> data = (resp == null || resp.getData() == null)
                ? Collections.<OptionsInstruments.OptionInstrument>emptyList()
                : resp.getData();

        Set<String> distinct = new HashSet<String>();
        for (OptionsInstruments.OptionInstrument oi : data) {
            if (oi.getExpiry() != null) distinct.add(oi.getExpiry());
        }

        List<LocalDate> exps = new ArrayList<LocalDate>();
        for (String s : distinct) {
            try {
                exps.add(LocalDate.parse(s));
            } catch (Throwable ignored) {
            }
        }
        LocalDate today = LocalDate.now();

        List<LocalDate> out = exps.stream()
                .filter(d -> !d.isBefore(today))
                .sorted()
                .limit(Math.max(1, count))
                .collect(Collectors.toList());
        return Result.ok(out);
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
            String key = oi.getInstrument_key();
            OptionGreekResponse.OptionGreek g = (key == null) ? null : greeks.get(key);
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
            String key = oi.getInstrument_key();
            OptionGreekResponse.OptionGreek g = (key == null) ? null : greeks.get(key);
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
     * Max Pain strike (quick heuristic: strike with max combined OI across CE+PE).
     */
    public Result<BigDecimal> getMaxPainStrike(String underlyingKey, LocalDate expiry) {
        if (isBlank(underlyingKey) || expiry == null) return Result.fail("BAD_REQUEST", "params required");

        List<OptionsInstruments.OptionInstrument> instruments = fetchInstruments(underlyingKey, expiry);
        if (instruments.isEmpty()) return Result.fail("NOT_FOUND", "No option instruments for expiry");

        Map<String, OptionGreekResponse.OptionGreek> greeks = fetchGreeksMap(instruments);
        if (greeks == null || greeks.isEmpty()) return Result.fail("NOT_FOUND", "No greeks returned");

        // Sum OI per strike across CE+PE
        Map<Integer, Long> strikeSumOi = new HashMap<Integer, Long>();
        for (OptionsInstruments.OptionInstrument oi : instruments) {
            String key = oi.getInstrument_key();
            OptionGreekResponse.OptionGreek g = (key == null) ? null : greeks.get(key);
            if (g == null) continue;
            int k = oi.getStrike_price();
            long prev = strikeSumOi.containsKey(k) ? strikeSumOi.get(k) : 0L;
            strikeSumOi.put(k, prev + Math.max(0, g.getOi()));
        }
        if (strikeSumOi.isEmpty()) return Result.fail("NOT_FOUND", "No strike OI");

        // Max combined OI
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
        } catch (Throwable t) {
            log.debug("fetchInstruments failed: {}", t.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Call greeks in batches to respect any CSV length limits.
     */
    private Map<String, OptionGreekResponse.OptionGreek> fetchGreeksMap(List<OptionsInstruments.OptionInstrument> instruments) {
        Map<String, OptionGreekResponse.OptionGreek> out = new HashMap<String, OptionGreekResponse.OptionGreek>();
        List<String> keys = new ArrayList<String>(instruments.size());
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
            } catch (Throwable t) {
                log.debug("fetchGreeksMap batch failed: {}", t.getMessage());
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
        switch (t) {
            case CALL:
                return "CE";
            case PUT:
                return "PE";
            default:
                return "";
        }
    }
}
