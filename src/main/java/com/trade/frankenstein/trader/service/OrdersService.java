package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.upstox.api.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
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
    private UpstoxService upstoxService;

    private String defaultProduct = "MIS";

    private String defaultValidity = "DAY";

    private int workingTtlMinutes = 120;

    /**
     * Minimal in-memory tracker so isOrderWorking() has a fallback even if broker status isn't queried here.
     */
    private final Map<String, Instant> workingOrders = new ConcurrentHashMap<>();

    /**
     * Place an order; market-hours gated unless AMO=true or testMode=true.
     */
    public Result<PlaceOrderResponse> placeOrder(PlaceOrderRequest req) {
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

            if (tradeMode.isSandBox() || !req.isIsAmo() || !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing orders");
            }

            // Risk guardrails
            Result<Void> guard = risk.checkOrder(req);
            if (guard == null || !guard.isOk()) {
                return Result.fail(guard == null ? "RISK_ERROR" : guard.getErrorCode(),
                        guard == null ? "Risk check failed" : guard.getError());
            }

            PlaceOrderResponse placed = upstox.placeOrder(req);

            // inform risk throttle + SSE
            try {
                risk.noteOrderPlaced();
            } catch (Exception ignored) {
                log.error("risk.noteOrderPlaced failed", ignored);
            }
            try {
                stream.send("order.placed", placed);
            } catch (Exception ignored) {
                log.error("stream.send failed", ignored);
            }

            return Result.ok(placed);
        } catch (Exception t) {
            log.error("placeOrder failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Modify an order; market-hours gated unless testMode=true.
     */
    public Result<ModifyOrderResponse> modifyOrder(ModifyOrderRequest req) {
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "ModifyOrderRequest is required");
            if (tradeMode.isSandBox() || !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for modifying orders");
            }
            ModifyOrderResponse r = upstox.modifyOrder(req);
            try {
                stream.send("order.modified", r);
            } catch (Exception ignored) {
                log.error("stream send failed", ignored);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("modifyOrder failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Cancel an order; market-hours gated unless testMode=true.
     */
    public Result<CancelOrderResponse> cancelOrder(String orderId) {
        try {
            if (orderId == null || orderId.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "orderId is required");
            }
            if (tradeMode.isSandBox() || !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for cancelling orders");
            }
            CancelOrderResponse r = upstox.cancelOrder(orderId);
            try {
                stream.send("order.cancelled", r);
            } catch (Exception ignored) {
                log.error("stream send failed", ignored);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("cancelOrder failed", t);
            return Result.fail(t);
        }
    }

    // =========================================================================
    // READ/ANALYTICS passthroughs (no market-hours gating) -------------------
    // =========================================================================

    public Result<GetOrderDetailsResponse> getOrder(String orderId) {
        try {
            if (orderId == null || orderId.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "orderId is required");
            }
            return Result.ok(upstox.getOrderDetails(orderId));
        } catch (Exception t) {
            log.error("getOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<GetOrderResponse> listOrders(String order_id, String tag) {
        try {
            GetOrderResponse response = upstox.getOrderHistory(order_id, tag);
            if (response == null || response.getData() == null) {
                return Result.fail("No orders present");
            }
            return Result.ok(response);
        } catch (Exception t) {
            log.error("listOrders failed", t);
            return Result.fail(t);
        }
    }


    // =========================================================================
    // Helpers ----------------------------------------------------------------
    // =========================================================================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * IST market hours gate: Mon–Fri, 09:15–15:30.
     */
    private static boolean isMarketOpenNowIst() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    /**
     * Guard: abort if 1m bar spread is too wide relative to close.
     * Uses OHLC live API as a proxy for bid/ask when depth is unavailable.
     */
    public boolean preflightSlippageGuard(String instrumentKey, BigDecimal maxSpreadPct) {
        try {
            // 1) Prefer true bid/ask from depth (FULL quotes)
            Optional<UpstoxService.BestBidAsk> oba = upstoxService.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid;
                double ask = oba.get().ask;
                if (bid > 0 && ask > 0) {
                    double mid = (bid + ask) / 2.0;
                    if (mid <= 0) return true; // can't evaluate → allow
                    double spreadPct = (ask - bid) / mid;
                    return BigDecimal.valueOf(spreadPct).compareTo(maxSpreadPct) <= 0;
                }
            }
            // 2) Fallback: 1m OHLC proxy ((H-L)/Close)
            GetMarketQuoteOHLCResponseV3 q = upstoxService.getMarketOHLCQuote(instrumentKey, "I1");
            MarketQuoteOHLCV3 d = (q == null || q.getData() == null) ? null : q.getData().get(instrumentKey);
            if (d == null || d.getLiveOhlc() == null) return true; // can't evaluate → allow
            double hi = d.getLiveOhlc().getHigh();
            double lo = d.getLiveOhlc().getLow();
            double cl = d.getLiveOhlc().getClose();
            if (cl <= 0) return true; // allow if we can't compute properly
            double spread = (hi - lo) / cl;
            return BigDecimal.valueOf(spread).compareTo(maxSpreadPct) <= 0;
        } catch (Exception t) {
            return true; // be permissive if preflight fails
        }
    }


    public Result<PlaceOrderResponse> placeTargetOrder(String instrumentKey, int qty, Float targetPrice) {
        try {
            if (tradeMode.isSandBox() || !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing target orders");
            }
            if (instrumentKey == null || instrumentKey.trim().isEmpty() || qty <= 0 || targetPrice == null) {
                return Result.fail("BAD_REQUEST", "instrumentKey/qty/price are required");
            }
            PlaceOrderResponse r = upstox.placeTargetOrder(instrumentKey, qty, targetPrice);
            try {
                stream.send("order.placed", r);
            } catch (Exception ignored) {
                log.error("stream send failed");
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("placeTargetOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<PlaceOrderResponse> placeStopLossOrder(String instrumentKey, int qty, Float triggerPrice) {
        try {
            if (tradeMode.isSandBox() || !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing stop-loss orders");
            }
            if (instrumentKey == null || instrumentKey.trim().isEmpty() || qty <= 0 || triggerPrice == null) {
                return Result.fail("BAD_REQUEST", "instrumentKey/qty/trigger are required");
            }
            PlaceOrderResponse r = upstox.placeStopLossOrder(instrumentKey, qty, triggerPrice);
            try {
                stream.send("order.placed", r);
            } catch (Exception ignored) {
                log.error("stream send failed {}", ignored);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("placeStopLossOrder failed", t);
            return Result.fail(t);
        }
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

    public Result<Boolean> isOrderWorking(String orderId) {
        try {
            if (orderId == null || orderId.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "orderId is required");
            }
            boolean working = upstox.isOrderWorking(orderId);
            return Result.ok(working);
        } catch (Exception t) {
            log.error("isOrderWorking failed", t);
            return Result.fail(t);
        }
    }


    private void markWorking(String orderId) {
        if (orderId != null) workingOrders.put(orderId, Instant.now());
    }

    /**
     * Mid-price from true bid/ask when available; fallback to OHLC mid, then LTP.
     */
    public Optional<BigDecimal> getBidAskMid(String instrumentKey) {
        try {
            // Depth first
            Optional<UpstoxService.BestBidAsk> oba = upstoxService.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid;
                double ask = oba.get().ask;
                if (bid > 0 && ask > 0) {
                    return Optional.of(BigDecimal.valueOf((bid + ask) / 2.0).setScale(2, RoundingMode.HALF_UP));
                }
            }

            // OHLC mid as proxy
            GetMarketQuoteOHLCResponseV3 ohlc = upstoxService.getMarketOHLCQuote(instrumentKey, "1minute");
            MarketQuoteOHLCV3 d = (ohlc == null || ohlc.getData() == null) ? null : ohlc.getData().get(instrumentKey);
            if (d != null && d.getLiveOhlc() != null) {
                double hi = d.getLiveOhlc().getHigh();
                double lo = d.getLiveOhlc().getLow();
                if (hi > 0 && lo > 0) {
                    return Optional.of(BigDecimal.valueOf((hi + lo) / 2.0).setScale(2, RoundingMode.HALF_UP));
                }
            }

            // Fallback: LTP
            GetMarketQuoteLastTradedPriceResponseV3 ltp = upstoxService.getMarketLTPQuote(instrumentKey);
            double px = (ltp != null && ltp.getData() != null && ltp.getData().get(instrumentKey) != null)
                    ? ltp.getData().get(instrumentKey).getLastPrice() : 0.0;
            return px > 0 ? Optional.of(BigDecimal.valueOf(px).setScale(2, RoundingMode.HALF_UP)) : Optional.empty();
        } catch (Exception t) {
            return Optional.empty();
        }
    }

    /**
     * Spread % from true bid/ask when available; fallback to ((H-L)/Close).
     */
    public Optional<BigDecimal> getSpreadPct(String instrumentKey) {
        try {
            // Depth first
            Optional<UpstoxService.BestBidAsk> oba = upstoxService.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid;
                double ask = oba.get().ask;
                if (bid > 0 && ask > 0) {
                    double mid = (bid + ask) / 2.0;
                    if (mid > 0) {
                        double pct = (ask - bid) / mid;
                        return Optional.of(BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP));
                    }
                }
            }
            // Fallback: 1m OHLC proxy
            GetMarketQuoteOHLCResponseV3 ohlc = upstoxService.getMarketOHLCQuote(instrumentKey, "I1");
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
}
