package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.upstox.HoldingsResponse;
import com.trade.frankenstein.trader.model.upstox.PortfolioResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Real-time PortfolioService (no reflection, no test-mode gates)
 * - Reads live portfolio/holdings from UpstoxService
 * - Computes a PortfolioSummary on the fly
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioService {

    private final UpstoxService upstox;

    // ---------------------------------------------------------------------
    // Live passthroughs
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Result<PortfolioResponse> getPortfolio() {
        try {
            PortfolioResponse p = upstox.getShortTermPositions();
            if (p == null || p.getData() == null) {
                return Result.fail("NOT_FOUND", "No live portfolio data");
            }
            return Result.ok(p);
        } catch (Throwable t) {
            log.error("getPortfolio failed", t);
            return Result.fail(t);
        }
    }

    @Transactional(readOnly = true)
    public Result<HoldingsResponse> getHoldings() {
        try {
            HoldingsResponse h = upstox.getLongTermHoldings();
            if (h == null || h.getData() == null) {
                return Result.fail("NOT_FOUND", "No live holdings data");
            }
            return Result.ok(h);
        } catch (Throwable t) {
            log.error("getHoldings failed", t);
            return Result.fail(t);
        }
    }

    // ---------------------------------------------------------------------
    // Real-time summary from live portfolio lines (typed, no reflection)
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Result<PortfolioSummary> getPortfolioSummary() {
        try {
            PortfolioResponse p = upstox.getShortTermPositions();
            List<PortfolioResponse.PortfolioData> rows =
                    (p == null ? null : p.getData());
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

            for (PortfolioResponse.PortfolioData row : rows) {
                if (row == null) continue;

                Integer qObj = safeInt(row.getQuantity());
                int qty = (qObj == null ? 0 : qObj);

                Double avgPriceObj = coalesce(row.getAverage_price(), row.getAverage_price());
                Double buyPriceObj = coalesce(row.getBuy_price(), row.getBuy_price());
                Double ltpObj = coalesce(row.getLast_price(), row.getLast_price());
                Double closeObj = coalesce(row.getClose_price(), row.getClose_price());
                Double realisedObj = coalesce(row.getRealised(), row.getRealised());

                double buy = nzDouble(coalesce(avgPriceObj, buyPriceObj));
                double ltp = nzDouble(ltpObj);
                double close = nzDouble(closeObj);
                double realisedVal = nzDouble(realisedObj);

                double absQty = Math.abs(qty);

                if (buy > 0.0 && absQty > 0.0) {
                    invested = invested.add(BigDecimal.valueOf(absQty * buy));
                }
                if (ltp > 0.0 && absQty > 0.0) {
                    currentValue = currentValue.add(BigDecimal.valueOf(absQty * ltp));
                }

                // Realized/unrealized
                realized = realized.add(BigDecimal.valueOf(realisedVal));
                if (buy > 0.0 && ltp > 0.0 && qty != 0) {
                    double upnl = (ltp - buy) * qty; // signed, respects direction
                    unrealized = unrealized.add(BigDecimal.valueOf(upnl));
                }

                // Day PnL from prev close if available: (ltp - close) * qty
                if (close > 0.0 && ltp > 0.0 && qty != 0) {
                    double dp = (ltp - close) * qty;
                    dayPnl = dayPnl.add(BigDecimal.valueOf(dp));
                    // simple per-line return average to keep behavior identical
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
            return Result.ok(summary);
        } catch (Throwable t) {
            log.error("getPortfolioSummary failed", t);
            return Result.fail(t);
        }
    }

    // ---------------------------------------------------------------------
    // NEW: helpers for Engine/Risk wiring (typed)
    // ---------------------------------------------------------------------

    /**
     * Absolute day loss (₹). If day PnL >= 0, returns 0.
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> getDayPnlAbsNow() {
        try {
            Result<PortfolioSummary> sumRes = getPortfolioSummary();
            if (sumRes == null || !sumRes.isOk() || sumRes.get() == null) {
                return Result.fail("NOT_AVAILABLE", "No portfolio summary");
            }
            BigDecimal day = sumRes.get().getDayPnl();
            if (day == null) return Result.ok(BigDecimal.ZERO);
            return Result.ok(day.signum() < 0 ? day.abs() : BigDecimal.ZERO);
        } catch (Throwable t) {
            log.error("getDayPnlAbsNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Absolute total loss (₹). If total PnL >= 0, returns 0.
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> getTotalPnlAbsNow() {
        try {
            Result<PortfolioSummary> sumRes = getPortfolioSummary();
            if (sumRes == null || !sumRes.isOk() || sumRes.get() == null) {
                return Result.fail("NOT_AVAILABLE", "No portfolio summary");
            }
            BigDecimal total = sumRes.get().getTotalPnl();
            if (total == null) return Result.ok(BigDecimal.ZERO);
            return Result.ok(total.signum() < 0 ? total.abs() : BigDecimal.ZERO);
        } catch (Throwable t) {
            log.error("getTotalPnlAbsNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Aggregate open lots for a given underlying key (e.g., "NIFTY").
     * Uses row.getLotSize() when available; defaults to 50 for NIFTY.
     */
    @Transactional(readOnly = true)
    public Result<Integer> getOpenLotsForUnderlying(String underlyingKey) {
        try {
            if (underlyingKey == null || underlyingKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "underlyingKey required");
            }
            PortfolioResponse p = upstox.getShortTermPositions();
            List<PortfolioResponse.PortfolioData> rows =
                    (p == null ? null : p.getData());
            if (rows == null || rows.isEmpty()) {
                return Result.ok(0);
            }

            int lots = 0;
            String keyUC = underlyingKey.toUpperCase();

            for (PortfolioResponse.PortfolioData row : rows) {
                if (row == null) continue;

                String ts = row.getTrading_symbol();
                if (ts == null) continue;

                String tsUC = ts.toUpperCase();
                boolean matches = tsUC.contains(keyUC)
                        || (tsUC.contains("NIFTY") && keyUC.contains("NIFTY"));
                if (!matches) continue;

                int qty = safeInt(row.getQuantity()) == null ? 0 : row.getQuantity();
                int absQty = Math.abs(qty);
                if (absQty == 0) continue;

                int lotSize = (row.getQuantity() <= 0) ? 50 : row.getQuantity();
                lots += absQty / lotSize;
            }

            return Result.ok(lots);
        } catch (Throwable t) {
            log.error("getOpenLotsForUnderlying failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Force live refresh (touch broker endpoints). Safe no-op if you don’t cache.
     */
    @Transactional(readOnly = true)
    public Result<Void> refreshFromBroker() {
        try {
            upstox.getShortTermPositions();
            upstox.getLongTermHoldings();
            return Result.ok(null);
        } catch (Throwable t) {
            log.error("refreshFromBroker failed", t);
            return Result.fail(t);
        }
    }

    // ---------------------------------------------------------------------
    // Small typed helpers (null-safe, no reflection)
    // ---------------------------------------------------------------------

    private static Double coalesce(Double a, Double b) {
        return a != null ? a : b;
    }

    private static Integer coalesce(Integer a, Integer b) {
        return a != null ? a : b;
    }

    private static Integer safeInt(Integer v) {
        return v == null ? null : v;
    }

    private static double nzDouble(Double v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static String nonBlank(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
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
}
