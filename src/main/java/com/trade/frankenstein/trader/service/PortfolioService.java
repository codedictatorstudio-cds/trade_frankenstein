package com.trade.frankenstein.trader.service;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.service.options.OptionChainService;
import com.upstox.api.GetHoldingsResponse;
import com.upstox.api.GetPositionResponse;
import com.upstox.api.MarketQuoteOptionGreekV3;
import com.upstox.api.PositionData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * PortfolioService (Java 8, no reflection)
 * - Reads live positions/holdings from UpstoxService
 * - Computes a PortfolioSummary with a tiny 3s cache via FastStateStore
 * - All methods require user login (AuthCodeHolder guard)
 */
@Service
@Slf4j
public class PortfolioService {

    @Autowired
    private EventPublisher bus;
    @Autowired
    private OptionChainService optionChain;
    @Autowired
    private UpstoxService upstox;
    @Autowired
    private FastStateStore fast; // Optional: present in most deployments

    // ---------------------------------------------------------------------
    // Live passthroughs
    // ---------------------------------------------------------------------

    private static double safeDouble(java.math.BigDecimal v) {
        try {
            return v == null ? 0.0 : v.doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double nzDouble(Double v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    // ---------------------------------------------------------------------
    // Real-time summary from live portfolio lines (typed, no reflection)
    // ---------------------------------------------------------------------

    private static int nzInt(Integer v) {
        return v == null ? 0 : v.intValue();
    }

    // ---------------------------------------------------------------------
    // NEW: helpers for Engine/Risk wiring (typed)
    // ---------------------------------------------------------------------

    private static Integer parseStrike(String tsUC) {
        if (tsUC == null) return null;
        // Find the last 4-6 digit block (handles strikes like 19850, 22500)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4,6})(?!.*\\d)").matcher(tsUC);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Result<GetPositionResponse> getPortfolio() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            // Prefer generic names to avoid coupling
            GetPositionResponse p;
            try {
                p = upstox.getShortTermPositions();
            } catch (Throwable ignore) {
                p = upstox.getShortTermPositions();
            }
            if (p == null || p.getData() == null) {
                return Result.fail("NOT_FOUND", "No live portfolio data");
            }
            return Result.ok(p);
        } catch (Exception t) {
            log.error("getPortfolio failed", t);
            return Result.fail(t);
        }
    }

    @Transactional(readOnly = true)
    public Result<GetHoldingsResponse> getHoldings() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            GetHoldingsResponse h;
            try {
                h = upstox.getLongTermHoldings();
            } catch (Throwable ignore) {
                h = upstox.getLongTermHoldings();
            }
            if (h == null || h.getData() == null) {
                return Result.fail("NOT_FOUND", "No live holdings data");
            }
            try {
                com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                d.addProperty("count", (h) == null || (h).getData() == null ? 0 : (h).getData().size());
                audit("portfolio.holdings", d);
            } catch (Throwable ignore) {
            }
            return Result.ok(h);
        } catch (Exception t) {
            log.error("getHoldings failed", t);
            return Result.fail(t);
        }
    }

    @Transactional(readOnly = true)
    public Result<PortfolioSummary> getPortfolioSummary() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            // try a tiny cache first (3s)
            PortfolioSummary cached = readSummaryCache();
            if (cached != null) return Result.ok(cached);

            GetPositionResponse p;
            try {
                p = upstox.getShortTermPositions();
            } catch (Throwable ignore) {
                p = upstox.getShortTermPositions();
            }
            List<PositionData> rows = (p == null ? null : p.getData());
            if (rows == null || rows.isEmpty()) {
                return Result.fail("NOT_FOUND", "No live portfolio data");
            }

            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal currentValue = BigDecimal.ZERO;
            BigDecimal realized = BigDecimal.ZERO;
            BigDecimal unrealized = BigDecimal.ZERO;
            BigDecimal dayPnl = BigDecimal.ZERO;
            BigDecimal dayPnlPctAgg = BigDecimal.ZERO;

            int countedLines = 0;

            for (PositionData row : rows) {
                if (row == null) continue;

                int qty = nzInt(row.getQuantity());
                int absQty = Math.abs(qty);

                double avg = nzDouble(row.getAveragePrice().doubleValue());  // fallback to buyPrice if needed
                if (avg <= 0.0) avg = nzDouble(row.getBuyPrice().doubleValue());

                double ltp = nzDouble(row.getLastPrice().doubleValue());
                if (ltp <= 0.0) ltp = nzDouble(row.getLastPrice().doubleValue()); // alternate naming

                double close = nzDouble(row.getClosePrice().doubleValue());
                double realisedVal = nzDouble(row.getRealised().doubleValue());

                if (avg > 0.0 && absQty > 0) {
                    invested = invested.add(BigDecimal.valueOf(absQty * avg));
                }
                if (ltp > 0.0 && absQty > 0) {
                    currentValue = currentValue.add(BigDecimal.valueOf(absQty * ltp));
                }

                // Realized/unrealized
                realized = realized.add(BigDecimal.valueOf(realisedVal));
                if (avg > 0.0 && ltp > 0.0 && qty != 0) {
                    double upnl = (ltp - avg) * qty; // signed, respects direction
                    unrealized = unrealized.add(BigDecimal.valueOf(upnl));
                }

                // Day PnL from prev close if available: (ltp - close) * qty
                if (close > 0.0 && ltp > 0.0 && qty != 0) {
                    double dp = (ltp - close) * qty;
                    dayPnl = dayPnl.add(BigDecimal.valueOf(dp));
                    // simple per-line return average
                    dayPnlPctAgg = dayPnlPctAgg.add(BigDecimal.valueOf(((ltp - close) / close) * 100.0));
                }

                countedLines++;
            }

            BigDecimal totalPnl = realized.add(unrealized);
            BigDecimal totalPnlPct = (invested.signum() > 0)
                    ? totalPnl.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            BigDecimal dayPnlPct = (countedLines > 0)
                    ? dayPnlPctAgg.divide(BigDecimal.valueOf(countedLines), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            PortfolioSummary summary = new PortfolioSummary(
                    invested.setScale(2, RoundingMode.HALF_UP),
                    currentValue.setScale(2, RoundingMode.HALF_UP),
                    totalPnl.setScale(2, RoundingMode.HALF_UP),
                    totalPnlPct.setScale(2, RoundingMode.HALF_UP),
                    dayPnl.setScale(2, RoundingMode.HALF_UP),
                    dayPnlPct.setScale(2, RoundingMode.HALF_UP),
                    countedLines
            );

            writeSummaryCache(summary);
            try {
                JsonObject d = new JsonObject();
                d.addProperty("invested", summary.getTotalInvested().doubleValue());
                d.addProperty("current", summary.getCurrentValue().doubleValue());
                d.addProperty("totalPnlAbs", summary.getTotalPnlPct().doubleValue());
                d.addProperty("totalPnlPct", summary.getTotalPnlPct().doubleValue());
                d.addProperty("dayPnlAbs", summary.getDayPnl().doubleValue());
                d.addProperty("dayPnlPct", summary.getDayPnlPct().doubleValue());
                d.addProperty("lines", summary.getPositionsCount());
                audit("portfolio.summary", d);
            } catch (Throwable ignore) {
            }
            return Result.ok(summary);
        } catch (Exception t) {
            log.error("getPortfolioSummary failed", t);
            return Result.fail(t);
        }
    }

    // ---------------------------------------------------------------------
    // Small typed helpers

    /**
     * Absolute day loss (₹). If day PnL >= 0, returns 0.
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> getDayPnlAbsNow() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            Result<PortfolioSummary> sumRes = getPortfolioSummary();
            if (sumRes == null || !sumRes.isOk() || sumRes.get() == null) {
                return Result.fail("NOT_AVAILABLE", "No portfolio summary");
            }
            BigDecimal day = sumRes.get().getDayPnl();
            if (day == null) return Result.ok(BigDecimal.ZERO);
            return Result.ok(day.signum() < 0 ? day.abs() : BigDecimal.ZERO);
        } catch (Exception t) {
            log.error("getDayPnlAbsNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Absolute total loss (₹). If total PnL >= 0, returns 0.
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> getTotalPnlAbsNow() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            Result<PortfolioSummary> sumRes = getPortfolioSummary();
            if (sumRes == null || !sumRes.isOk() || sumRes.get() == null) {
                return Result.fail("NOT_AVAILABLE", "No portfolio summary");
            }
            BigDecimal total = sumRes.get().getTotalPnl();
            if (total == null) return Result.ok(BigDecimal.ZERO);
            return Result.ok(total.signum() < 0 ? total.abs() : BigDecimal.ZERO);
        } catch (Exception t) {
            log.error("getTotalPnlAbsNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Aggregate open lots for a given underlying key (e.g., "NIFTY").
     * Heuristic lot-size mapping without reflection.
     */
    @Transactional(readOnly = true)
    public Result<Integer> getOpenLotsForUnderlying(String underlyingKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            if (underlyingKey == null || underlyingKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "underlyingKey required");
            }
            GetPositionResponse p;
            try {
                p = upstox.getShortTermPositions();
            } catch (Throwable ignore) {
                p = upstox.getShortTermPositions();
            }
            List<PositionData> rows = (p == null ? null : p.getData());
            if (rows == null || rows.isEmpty()) {
                return Result.ok(0);
            }

            int lots = 0;
            String keyUC = underlyingKey.toUpperCase();

            for (PositionData row : rows) {
                if (row == null) continue;

                String ts = row.getTradingSymbol();
                if (ts == null) continue;

                String tsUC = ts.toUpperCase();
                boolean matches = tsUC.contains(keyUC)
                        || (tsUC.contains("NIFTY") && keyUC.contains("NIFTY"));

                if (!matches) continue;

                int qty = nzInt(row.getQuantity());
                int absQty = Math.abs(qty);
                if (absQty == 0) continue;

                int lotSize = defaultLotSizeForSymbol(tsUC);
                if (lotSize <= 0) lotSize = 1;

                lots += absQty / lotSize;
            }

            return Result.ok(lots);
        } catch (Exception t) {
            log.error("getOpenLotsForUnderlying failed", t);
            return Result.fail(t);
        }
    }

    public int defaultLotSizeForSymbol(String symUC) {
        if (symUC == null) return 1;
        if (symUC.contains("BANKNIFTY")) return 35;   // heuristic
        if (symUC.contains("FINNIFTY")) return 40;    // heuristic
        if (symUC.contains("MIDCPNIFTY")) return 25;  // heuristic
        if (symUC.contains("NIFTY")) return 75;       // heuristic default
        return 1;                                     // equities or unknown
    }

    // ---------------------------------------------------------------------

    /**
     * Net delta (signed) for the given underlying in equivalent underlying units (shares).
     * Uses live option greeks via OptionChainService when available; falls back to 1.0 for futures.
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> getNetDeltaForUnderlying(String underlyingKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            Result<PortfolioGreeks> g = getNetGreeksForUnderlying(underlyingKey);
            if (g == null || !g.isOk() || g.get() == null) {
                return Result.fail("NOT_AVAILABLE", "No greeks available");
            }
            return Result.ok(g.get().getNetDelta());
        } catch (Exception t) {
            log.error("getNetDeltaForUnderlying failed", t);
            return Result.fail(t);
        }
    }


    // ---------------------------------------------------------------------
    // Net Greeks / Delta exposure (for Engine/Decision hedging)
    // ---------------------------------------------------------------------

    /**
     * When DELTA_TARGET_HEDGE is ON, returns the signed delta gap (net delta vs. 0 target).
     * Positive value implies long delta (need to SELL hedge), negative implies short delta (need to BUY hedge).
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> getDeltaGapForUnderlying(String underlyingKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            Result<BigDecimal> nd = getNetDeltaForUnderlying(underlyingKey);
            if (nd == null || !nd.isOk()) return nd;
            // Target is 0 (delta-neutral). Gap == net delta.
            return Result.ok(nd.get());
        } catch (Exception t) {
            log.error("getDeltaGapForUnderlying failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Aggregated portfolio greeks for a given underlying. Delta is in equivalent underlying units (shares),
     * while gamma/theta/vega are naive sums scaled by lot size and quantity.
     */
    @Transactional(readOnly = true)
    public Result<PortfolioGreeks> getNetGreeksForUnderlying(String underlyingKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            GetPositionResponse p;
            try {
                p = upstox.getShortTermPositions();
            } catch (Throwable ignore) {
                p = upstox.getShortTermPositions();
            }
            java.util.List<com.upstox.api.PositionData> rows = (p == null ? null : p.getData());
            if (rows == null || rows.isEmpty()) {
                return Result.fail("NOT_FOUND", "No live portfolio data");
            }

            String keyUC = underlyingKey == null ? "" : underlyingKey.toUpperCase();
            java.math.BigDecimal netDelta = java.math.BigDecimal.ZERO;
            java.math.BigDecimal netGamma = java.math.BigDecimal.ZERO;
            java.math.BigDecimal netTheta = java.math.BigDecimal.ZERO;
            java.math.BigDecimal netVega = java.math.BigDecimal.ZERO;

            // Cache greeks by expiry to avoid repeated lookups
            java.util.Map<java.time.LocalDate, java.util.Map<String, MarketQuoteOptionGreekV3>> greeksByExp =
                    new java.util.HashMap<java.time.LocalDate, java.util.Map<String, MarketQuoteOptionGreekV3>>();

            for (com.upstox.api.PositionData row : rows) {
                if (row == null) continue;
                String ts = row.getTradingSymbol();
                if (ts == null) continue;
                String tsUC = ts.toUpperCase();

                boolean matches = (keyUC.isEmpty())
                        || tsUC.contains(keyUC)
                        || (tsUC.contains("NIFTY") && keyUC.contains("NIFTY"));

                if (!matches) continue;

                int qty = nzInt(row.getQuantity());
                if (qty == 0) continue;

                int lotSize = defaultLotSizeForSymbol(tsUC);
                if (lotSize <= 0) lotSize = 1;
                int signedLots = qty; // quantity is already signed in lots or pieces

                // Options path: try live greek
                boolean isCall = tsUC.contains("CE");
                boolean isPut = tsUC.contains("PE");
                boolean isFut = tsUC.contains("FUT");

                if (isCall || isPut) {
                    MarketQuoteOptionGreekV3 g = findGreekForTradingSymbol(underlyingKey, tsUC, greeksByExp);
                    if (g != null) {
                        double d = safeDouble(BigDecimal.valueOf(g.getDelta()));
                        double gm = safeDouble(BigDecimal.valueOf(g.getGamma()));
                        double th = safeDouble(BigDecimal.valueOf(g.getTheta()));
                        double vg = safeDouble(BigDecimal.valueOf(g.getVega()));

                        java.math.BigDecimal scale = java.math.BigDecimal.valueOf(lotSize).multiply(java.math.BigDecimal.valueOf(signedLots));
                        netDelta = netDelta.add(java.math.BigDecimal.valueOf(d).multiply(scale));
                        netGamma = netGamma.add(java.math.BigDecimal.valueOf(gm).multiply(scale));
                        netTheta = netTheta.add(java.math.BigDecimal.valueOf(th).multiply(scale));
                        netVega = netVega.add(java.math.BigDecimal.valueOf(vg).multiply(scale));
                        continue;
                    }
                    // No greek? fall through with conservative zero (don't guess).
                }

                // Futures / equity path: approximate delta ~ 1.0
                if (isFut || (!isCall && !isPut)) {
                    java.math.BigDecimal scale = java.math.BigDecimal.valueOf(lotSize).multiply(java.math.BigDecimal.valueOf(signedLots));
                    netDelta = netDelta.add(scale); // 1.0 * qty * lotSize
                }
            }

            PortfolioGreeks out = new PortfolioGreeks(netDelta, netGamma, netTheta, netVega);
            return Result.ok(out);
        } catch (Exception t) {
            log.error("getNetGreeksForUnderlying failed", t);
            return Result.fail(t);
        }
    }

    // Attempts to locate a greek for a trading symbol by scanning nearest expiries and matching type/strike in instrument key
    private MarketQuoteOptionGreekV3 findGreekForTradingSymbol(String underlyingKey,
                                                               String tsUC,
                                                               java.util.Map<java.time.LocalDate, java.util.Map<String, MarketQuoteOptionGreekV3>> greeksByExp) {
        try {
            if (optionChain == null) return null;

            String type = tsUC.contains("CE") ? "CE" : (tsUC.contains("PE") ? "PE" : null);
            if (type == null) return null;

            Integer strike = parseStrike(tsUC);
            if (strike == null) return null;

            Result<java.util.List<java.time.LocalDate>> expsRes = optionChain.listNearestExpiries(underlyingKey, 4);
            if (expsRes == null || !expsRes.isOk() || expsRes.get() == null) return null;

            for (java.time.LocalDate exp : expsRes.get()) {
                java.util.Map<String, MarketQuoteOptionGreekV3> m = greeksByExp.get(exp);
                if (m == null) {
                    Result<java.util.Map<String, MarketQuoteOptionGreekV3>> gRes = optionChain.getGreeksForExpiry(underlyingKey, exp);
                    if (gRes == null || !gRes.isOk() || gRes.get() == null) continue;
                    m = gRes.get();
                    greeksByExp.put(exp, m);
                }
                String needle = "_" + type + "_" + strike;
                for (java.util.Map.Entry<String, MarketQuoteOptionGreekV3> e : m.entrySet()) {
                    String ik = e.getKey();
                    if (ik != null && ik.toUpperCase().contains(needle)) {
                        return e.getValue();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Tiny cache (FastStateStore) helpers
    // ---------------------------------------------------------------------
    private PortfolioSummary readSummaryCache() {
        try {
            if (fast == null) return null;
            Optional<String> s = fast.get("pf:summary");
            if (!s.isPresent()) return null;
            String[] parts = s.get().split("\\|");
            if (parts.length != 7) return null;
            return new PortfolioSummary(
                    new BigDecimal(parts[0]),
                    new BigDecimal(parts[1]),
                    new BigDecimal(parts[2]),
                    new BigDecimal(parts[3]),
                    new BigDecimal(parts[4]),
                    new BigDecimal(parts[5]),
                    Integer.parseInt(parts[6])
            );
        } catch (Exception ignore) {
            return null;
        }
    }

    private void writeSummaryCache(PortfolioSummary ps) {
        try {
            if (fast == null || ps == null) return;
            String payload = ps.getTotalInvested().toPlainString() + "|" +
                    ps.getCurrentValue().toPlainString() + "|" +
                    ps.getTotalPnl().toPlainString() + "|" +
                    ps.getTotalPnlPct().toPlainString() + "|" +
                    ps.getDayPnl().toPlainString() + "|" +
                    ps.getDayPnlPct().toPlainString() + "|" +
                    ps.getPositionsCount();
            fast.put("pf:summary", payload, Duration.ofSeconds(3));
        } catch (Exception ignore) {
        }
    }

    // ---------------------------------------------------------------------
    // Auth guard
    // ---------------------------------------------------------------------
    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // DTO (Java-8 POJO)
    // ---------------------------------------------------------------------
    @Data
    @AllArgsConstructor
    public static class PortfolioSummary {
        private BigDecimal totalInvested;
        private BigDecimal currentValue;
        private BigDecimal totalPnl;
        private BigDecimal totalPnlPct;
        private BigDecimal dayPnl;
        private BigDecimal dayPnlPct;
        private int positionsCount;
    }

    // ---------------------------------------------------------------------
    // Aggregated Greeks DTO (Java 8 POJO)
    // ---------------------------------------------------------------------
    @Data
    @AllArgsConstructor
    public static class PortfolioGreeks {
        private BigDecimal netDelta;   // in underlying units (shares), signed
        private BigDecimal netGamma;
        private BigDecimal netTheta;
        private BigDecimal netVega;
    }

    // Kafkaesque audit helper (optional)
    private void audit(String event, com.google.gson.JsonObject data) {
        try {
            if (bus == null) return;
            java.time.Instant now = java.time.Instant.now();
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", event);
            o.addProperty("source", "portfolio");
            if (data != null) o.add("data", data);
            bus.publish(com.trade.frankenstein.trader.bus.EventBusConfig.TOPIC_AUDIT, "portfolio", o.toString());
        } catch (Throwable ignore) { /* best-effort */ }
    }
}
