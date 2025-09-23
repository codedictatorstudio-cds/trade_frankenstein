package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.upstox.api.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class OrdersService {

    @Autowired
    private UpstoxService upstox;
    @Autowired
    private RiskService risk;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private UpstoxTradeMode tradeMode;
    @Autowired
    private FastStateStore fast; // Step-3: idempotency / rate counters

    private String defaultProduct = "MIS";
    private String defaultValidity = "DAY";
    private int workingTtlMinutes = 120;

    /**
     * Minimal in-memory tracker so isOrderWorking() has a fallback even if broker status isn't queried here.
     */
    private final Map<String, Instant> workingOrders = new ConcurrentHashMap<>();

    // =====================================================================
    // WRITE PATHS (market-hours gating + risk + idempotency)
    // =====================================================================

    /**
     * Place an order.
     * Market-hours rule: allowed if (marketOpen) OR (AMO) OR (sandbox/test).
     */
    public Result<PlaceOrderResponse> placeOrder(PlaceOrderRequest req) {
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

            // Correct gating: block only when NOT sandbox AND NOT AMO AND market is closed
            if (!tradeMode.isSandBox() && !Boolean.TRUE.equals(req.isIsAmo()) && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing orders");
            }

            // Step-3: idempotency (120s). If we cannot hash fields reliably, fall back to req.toString()
            String idemKey = "order:idemp:" + hashDraftSafe(req);
            boolean first = fast.setIfAbsent(idemKey, "1", Duration.ofSeconds(120));
            if (!first) {
                return Result.fail("DUPLICATE", "Duplicate order (idempotency window 120s)");
            }

            // Risk guardrails
            Result<Void> guard = risk.checkOrder(req);
            if (guard == null || !guard.isOk()) {
                return Result.fail(guard == null ? "RISK_ERROR" : guard.getErrorCode(),
                        guard == null ? "Risk check failed" : guard.getError());
            }

            // Place with broker
            PlaceOrderResponse placed = upstox.placeOrder(req);

            // Side-effects: risk throttle + SSE
            try {
                risk.noteOrderPlaced();
            } catch (Exception ex) {
                log.warn("risk.noteOrderPlaced failed", ex);
            }
            try {
                stream.send("order.placed", placed);
            } catch (Exception ex) {
                log.warn("stream.send failed", ex);
            }

            return Result.ok(placed);
        } catch (Exception t) {
            log.error("placeOrder failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Modify an order.
     * Market-hours rule: allowed if (marketOpen) OR (sandbox/test).
     */
    public Result<ModifyOrderResponse> modifyOrder(ModifyOrderRequest req) {
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "ModifyOrderRequest is required");
            if (!tradeMode.isSandBox() && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for modifying orders");
            }
            ModifyOrderResponse r = upstox.modifyOrder(req);
            try {
                stream.send("order.modified", r);
            } catch (Exception ex) {
                log.warn("stream send failed", ex);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("modifyOrder failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Cancel an order.
     * Market-hours rule: allowed if (marketOpen) OR (sandbox/test).
     */
    public Result<CancelOrderResponse> cancelOrder(String orderId) {
        try {
            if (isBlank(orderId)) return Result.fail("BAD_REQUEST", "orderId is required");
            if (!tradeMode.isSandBox() && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for cancelling orders");
            }
            CancelOrderResponse r = upstox.cancelOrder(orderId);
            try {
                stream.send("order.cancelled", r);
            } catch (Exception ex) {
                log.warn("stream send failed", ex);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("cancelOrder failed", t);
            return Result.fail(t);
        }
    }

    // =====================================================================
    // READ PATHS
    // =====================================================================

    public Result<GetOrderDetailsResponse> getOrder(String orderId) {
        try {
            if (isBlank(orderId)) return Result.fail("BAD_REQUEST", "orderId is required");
            return Result.ok(upstox.getOrderDetails(orderId));
        } catch (Exception t) {
            log.error("getOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<GetOrderResponse> listOrders(String orderId, String tag) {
        try {
            GetOrderResponse response = upstox.getOrderHistory(orderId, tag);
            if (response == null || response.getData() == null) {
                return Result.fail("NOT_FOUND", "No orders present");
            }
            return Result.ok(response);
        } catch (Exception t) {
            log.error("listOrders failed", t);
            return Result.fail(t);
        }
    }

    public Result<Boolean> isOrderWorking(String orderId) {
        try {
            if (isBlank(orderId)) return Result.fail("BAD_REQUEST", "orderId is required");
            boolean working = upstox.isOrderWorking(orderId);
            return Result.ok(working);
        } catch (Exception t) {
            log.error("isOrderWorking failed", t);
            return Result.fail(t);
        }
    }

    // =====================================================================
    // Helpers (price/guards)
    // =====================================================================

    /**
     * Guard: abort if live spread is too wide.
     * Uses true bid/ask when available; otherwise (H-L)/Close from I1 bar.
     */
    public boolean preflightSlippageGuard(String instrumentKey, BigDecimal maxSpreadPct) {
        try {
            // 1) Prefer depth
            Optional<UpstoxService.BestBidAsk> oba = upstox.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid, ask = oba.get().ask;
                if (bid > 0 && ask > 0) {
                    double mid = (bid + ask) / 2.0;
                    if (mid <= 0) return true; // can't evaluate -> allow
                    double spreadPct = (ask - bid) / mid;
                    return BigDecimal.valueOf(spreadPct).compareTo(maxSpreadPct) <= 0;
                }
            }
            // 2) Fallback: I1 OHLC proxy
            GetMarketQuoteOHLCResponseV3 q = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            MarketQuoteOHLCV3 d = (q == null || q.getData() == null) ? null : q.getData().get(instrumentKey);
            if (d == null || d.getLiveOhlc() == null) return true; // allow if we can't compute
            double hi = d.getLiveOhlc().getHigh();
            double lo = d.getLiveOhlc().getLow();
            double cl = d.getLiveOhlc().getClose();
            if (cl <= 0) return true;
            double spread = (hi - lo) / cl;
            return BigDecimal.valueOf(spread).compareTo(maxSpreadPct) <= 0;
        } catch (Exception t) {
            return true; // permissive on failure
        }
    }

    /**
     * Mid-price from true bid/ask when available; fallback to OHLC mid, then LTP.
     */
    public Optional<BigDecimal> getBidAskMid(String instrumentKey) {
        try {
            // Depth
            Optional<UpstoxService.BestBidAsk> oba = upstox.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid, ask = oba.get().ask;
                if (bid > 0 && ask > 0) {
                    return Optional.of(BigDecimal.valueOf((bid + ask) / 2.0).setScale(2, RoundingMode.HALF_UP));
                }
            }
            // OHLC mid (I1)
            GetMarketQuoteOHLCResponseV3 ohlc = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            MarketQuoteOHLCV3 d = (ohlc == null || ohlc.getData() == null) ? null : ohlc.getData().get(instrumentKey);
            if (d != null && d.getLiveOhlc() != null) {
                double hi = d.getLiveOhlc().getHigh();
                double lo = d.getLiveOhlc().getLow();
                if (hi > 0 && lo > 0) {
                    return Optional.of(BigDecimal.valueOf((hi + lo) / 2.0).setScale(2, RoundingMode.HALF_UP));
                }
            }
            // LTP
            GetMarketQuoteLastTradedPriceResponseV3 ltp = upstox.getMarketLTPQuote(instrumentKey);
            double px = (ltp != null && ltp.getData() != null && ltp.getData().get(instrumentKey) != null)
                    ? ltp.getData().get(instrumentKey).getLastPrice() : 0.0;
            return px > 0 ? Optional.of(BigDecimal.valueOf(px).setScale(2, RoundingMode.HALF_UP)) : Optional.empty();
        } catch (Exception t) {
            return Optional.empty();
        }
    }

    /**
     * Spread % from true bid/ask when available; fallback to ((H-L)/Close) on I1.
     */
    public Optional<BigDecimal> getSpreadPct(String instrumentKey) {
        try {
            // Depth
            Optional<UpstoxService.BestBidAsk> oba = upstox.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid, ask = oba.get().ask;
                if (bid > 0 && ask > 0) {
                    double mid = (bid + ask) / 2.0;
                    if (mid > 0) {
                        double pct = (ask - bid) / mid;
                        return Optional.of(BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP));
                    }
                }
            }
            // I1 proxy
            GetMarketQuoteOHLCResponseV3 ohlc = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            MarketQuoteOHLCV3 d = (ohlc == null || ohlc.getData() == null) ? null : ohlc.getData().get(instrumentKey);
            if (d == null || d.getLiveOhlc() == null) return Optional.empty();
            double hi = d.getLiveOhlc().getHigh();
            double lo = d.getLiveOhlc().getLow();
            double cl = d.getLiveOhlc().getClose();
            if (hi <= 0 || lo <= 0 || cl <= 0) return Optional.empty();
            double pct = (hi - lo) / cl;
            return Optional.of(BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP));
        } catch (Exception t) {
            return Optional.empty();
        }
    }

    // =====================================================================
    // Convenience order helpers (limit/SL)
    // =====================================================================

    public Result<PlaceOrderResponse> placeTargetOrder(String instrumentKey, int qty, Float targetPrice) {
        try {
            if (!tradeMode.isSandBox() && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing target orders");
            }
            if (isBlank(instrumentKey) || qty <= 0 || targetPrice == null) {
                return Result.fail("BAD_REQUEST", "instrumentKey/qty/price are required");
            }
            PlaceOrderResponse r = upstox.placeTargetOrder(instrumentKey, qty, targetPrice);
            try {
                stream.send("order.placed", r);
            } catch (Exception ex) {
                log.warn("stream send failed", ex);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("placeTargetOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<PlaceOrderResponse> placeStopLossOrder(String instrumentKey, int qty, Float triggerPrice) {
        try {
            if (!tradeMode.isSandBox() && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing stop-loss orders");
            }
            if (isBlank(instrumentKey) || qty <= 0 || triggerPrice == null) {
                return Result.fail("BAD_REQUEST", "instrumentKey/qty/trigger are required");
            }
            PlaceOrderResponse r = upstox.placeStopLossOrder(instrumentKey, qty, triggerPrice);
            try {
                stream.send("order.placed", r);
            } catch (Exception ex) {
                log.warn("stream send failed", ex);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("placeStopLossOrder failed", t);
            return Result.fail(t);
        }
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * IST market hours: Mon–Fri, 09:15–15:30.
     */
    private static boolean isMarketOpenNowIst() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    private void markWorking(String orderId) {
        if (orderId != null) workingOrders.put(orderId, Instant.now());
    }

    /**
     * Build an idempotency hash from common request fields; fall back to req.toString() if needed.
     */
    private static String hashDraftSafe(PlaceOrderRequest req) {
        String s;
        try {
            String k = safe(() -> req.getInstrumentToken());
            String tt = safe(() -> String.valueOf(req.getTransactionType()));
            String ot = safe(() -> String.valueOf(req.getOrderType()));
            String pr = safe(() -> String.valueOf(req.getPrice()));
            String tq = safe(() -> String.valueOf(req.getQuantity()));
            s = String.join("|", k, tt, ot, pr, tq);
        } catch (Throwable ignore) {
            s = String.valueOf(req);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(dig);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String safe(SupplierX<String> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return "";
        }
    }

    @FunctionalInterface
    private interface SupplierX<T> {
        T get() throws Exception;
    }

    public Result<ModifyOrderResponse> amendOrderPrice(String orderId, Float newPrice) {
        try {
            if (tradeMode.isSandBox() || !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for modifying orders");
            }
            if (orderId == null || orderId.trim().isEmpty() || newPrice == null) {
                return Result.fail("BAD_REQUEST", "orderId/newPrice are required");
            }
            ModifyOrderResponse r = upstox.amendOrderPrice(orderId, newPrice);
            try {
                stream.send("order.modified", r);
            } catch (Exception ignored) {
                log.error("stream send failed", ignored);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("amendOrderPrice failed", t);
            return Result.fail(t);
        }
    }
}
