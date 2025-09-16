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

/**
 * Real-time PortfolioService:
 * - No DB repositories
 * - No testMode or market-hours gates
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
    // Real-time summary from live portfolio lines
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Result<PortfolioSummary> getPortfolioSummary() {
        try {
            PortfolioResponse p = upstox.getShortTermPositions();
            if (p == null || p.getData() == null || p.getData().isEmpty()) {
                return Result.fail("NOT_FOUND", "No live portfolio data");
            }

            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal currentValue = BigDecimal.ZERO;
            BigDecimal realized = BigDecimal.ZERO;
            BigDecimal unrealized = BigDecimal.ZERO;
            BigDecimal dayPnl = BigDecimal.ZERO;
            BigDecimal dayPnlPctAgg = BigDecimal.ZERO;

            int count = 0;

            for (Object row : p.getData()) {
                int qty = getInt(row, "getQuantity");
                double buy = getDouble(row, "getAverage_price"); // if missing, fall back to buy_price
                if (buy == 0.0) buy = getDouble(row, "getBuy_price");
                double ltp = getDouble(row, "getLast_price");
                double close = getDouble(row, "getClose_price");

                double absQty = Math.abs(qty);
                if (buy > 0.0) {
                    invested = invested.add(BigDecimal.valueOf(absQty * buy));
                }

                if (ltp > 0.0) {
                    currentValue = currentValue.add(BigDecimal.valueOf(absQty * ltp));
                }

                // Realized/unrealized
                double r = getDouble(row, "getRealised");
                if (r == 0.0) r = getDouble(row, "getRealized");
                realized = realized.add(BigDecimal.valueOf(r));

                if (buy > 0.0 && ltp > 0.0 && qty != 0) {
                    // signed PnL = (ltp - buy) * qty
                    double upnl = (ltp - buy) * qty;
                    unrealized = unrealized.add(BigDecimal.valueOf(upnl));
                }

                // Day PnL from prev close if available: (ltp - close) * qty
                if (close > 0.0 && ltp > 0.0 && qty != 0) {
                    double dp = (ltp - close) * qty;
                    dayPnl = dayPnl.add(BigDecimal.valueOf(dp));
                    // quick proxy for day %: average of per-line returns
                    dayPnlPctAgg = dayPnlPctAgg.add(BigDecimal.valueOf(((ltp - close) / close) * 100.0));
                }

                count++;
            }

            BigDecimal totalPnl = realized.add(unrealized);
            BigDecimal totalPnlPct = (invested.signum() > 0)
                    ? totalPnl.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            BigDecimal dayPnlPct = (count > 0)
                    ? dayPnlPctAgg.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            PortfolioSummary summary = new PortfolioSummary(
                    invested.setScale(2, RoundingMode.HALF_UP),
                    currentValue.setScale(2, RoundingMode.HALF_UP),
                    totalPnl.setScale(2, RoundingMode.HALF_UP),
                    totalPnlPct.setScale(2, RoundingMode.HALF_UP),
                    dayPnl.setScale(2, RoundingMode.HALF_UP),
                    dayPnlPct.setScale(2, RoundingMode.HALF_UP),
                    count
            );
            return Result.ok(summary);
        } catch (Throwable t) {
            log.error("getPortfolioSummary failed", t);
            return Result.fail(t);
        }
    }

    // ---------------------------------------------------------------------
    // Reflective getters (defensive against minor field name drift)
    // ---------------------------------------------------------------------

    private static double getDouble(Object bean, String getter) {
        try {
            java.lang.reflect.Method m = bean.getClass().getMethod(getter);
            Object v = m.invoke(bean);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    private static int getInt(Object bean, String getter) {
        try {
            java.lang.reflect.Method m = bean.getClass().getMethod(getter);
            Object v = m.invoke(bean);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String getString(Object bean, String getter) {
        try {
            java.lang.reflect.Method m = bean.getClass().getMethod(getter);
            Object v = m.invoke(bean);
            if (v instanceof String) {
                String s = ((String) v).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Throwable ignored) {
        }
        return null;
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
