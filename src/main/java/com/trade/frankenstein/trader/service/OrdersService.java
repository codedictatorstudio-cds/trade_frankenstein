package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.upstox.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.List;
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

            if (tradeMode.isSandBox() || !req.is_amo() || !isMarketOpenNowIst()) {
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

    public Result<OrderGetResponse> getOrder(String orderId) {
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

    public Result<OrderHistoryResponse> listOrders(String order_id, String tag) {
        try {
            OrderHistoryResponse response = upstox.getOrderHistory(order_id, tag);
            if (response == null || response.getData() == null) {
                return Result.fail("No orders present");
            }
            return Result.ok(response);
        } catch (Exception t) {
            log.error("listOrders failed", t);
            return Result.fail(t);
        }
    }

    public Result<OrderTradesResponse> getOrderTrades() {
        try {
            return Result.ok(upstox.getOrderTrades());
        } catch (Exception t) {
            log.error("getOrderTrades failed", t);
            return Result.fail(t);
        }
    }

    public Result<OrderTradesResponse> getOrderTrades(String order_id) {
        try {

            OrderTradesResponse response = upstox.getOrderTrades();
            String status = response.getStatus();

            List<OrderTradesResponse.TradeData> filteredData = response.getData().stream()
                    .filter(trade -> trade.getOrder_id().equalsIgnoreCase(order_id))
                    .toList();

            OrderTradesResponse filteredResponse = OrderTradesResponse.builder().status(status).data(filteredData).build();

            return Result.ok(filteredResponse);
        } catch (Exception t) {
            log.error("getOrderTrades failed", t);
            return Result.fail(t);
        }
    }

    public Result<PortfolioResponse> getPortfolio() {
        try {
            return Result.ok(upstox.getShortTermPositions());
        } catch (Exception t) {
            log.error("getPortfolio failed", t);
            return Result.fail(t);
        }
    }

    public Result<HoldingsResponse> getHoldings() {
        try {
            return Result.ok(upstox.getLongTermHoldings());
        } catch (Exception t) {
            log.error("getHoldings failed", t);
            return Result.fail(t);
        }
    }

    public Result<FundsResponse> getFunds(String segment) {
        try {
            return Result.ok(upstox.getFundAndMargin(segment));
        } catch (Exception t) {
            log.error("getFunds failed", t);
            return Result.fail(t);
        }
    }

    /**
     * CSV of instrument_keys per Upstox API, e.g. "NSE:RELIANCE-EQ,NFO:NIFTY24SEP17500CE".
     */
    public Result<LTP_Quotes> getLtpQuotes(String instrumentKeyCsv) {
        try {
            if (instrumentKeyCsv == null || instrumentKeyCsv.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "instrumentKeyCsv is required");
            }
            return Result.ok(upstox.getMarketLTPQuote(instrumentKeyCsv));
        } catch (Exception t) {
            log.error("getLtpQuotes failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Interval examples: "1minute","3minute","5minute","15minute".
     */
    public Result<OHLC_Quotes> getMarketOhlc(String instrumentKey, String interval) {
        try {
            if (isBlank(instrumentKey) || isBlank(interval)) {
                return Result.fail("BAD_REQUEST", "instrumentKey and interval are required");
            }
            return Result.ok(upstox.getMarketOHLCQuote(instrumentKey, interval));
        } catch (Exception t) {
            log.error("getMarketOhlc failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Intraday candles; e.g. candleType="minute", resolution="5".
     */
    public Result<IntradayCandleResponse> getIntradayCandles(String instrumentKey, String candleType, String resolution) {
        try {
            if (isBlank(instrumentKey) || isBlank(candleType) || isBlank(resolution)) {
                return Result.fail("BAD_REQUEST", "instrumentKey, candleType and resolution are required");
            }
            return Result.ok(upstox.getIntradayCandleData(instrumentKey, candleType, resolution));
        } catch (Exception t) {
            log.error("getIntradayCandles failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Historical candles; e.g. interval="day", from="2024-01-01", to="2024-12-31".
     */
    public Result<HistoricalCandleResponse> getHistoricalCandles(String instrument_key, String unit, String interval, String to_date, String from_date) {
        try {
            if (isBlank(instrument_key) || isBlank(interval) || isBlank(from_date) || isBlank(to_date)) {
                return Result.fail("BAD_REQUEST", "instrumentKey, interval, from, to are required");
            }
            return Result.ok(upstox.getHistoricalCandleData(instrument_key, unit, interval, to_date, from_date));
        } catch (Exception t) {
            log.error("getHistoricalCandles failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Option instruments for an underlying; expiry can be null for all.
     */
    public Result<OptionsInstruments> getOptionInstruments(String underlyingKey, String expiryDateIso) {
        try {
            if (isBlank(underlyingKey)) return Result.fail("BAD_REQUEST", "underlyingKey is required");
            return Result.ok(upstox.getOptionInstrument(underlyingKey, expiryDateIso));
        } catch (Exception t) {
            log.error("getOptionInstruments failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Greeks for CSV of instrument_keys.
     */
    public Result<OptionGreekResponse> getOptionGreeks(String instrumentKeyCsv) {
        try {
            if (isBlank(instrumentKeyCsv)) return Result.fail("BAD_REQUEST", "instrumentKeyCsv is required");
            return Result.ok(upstox.getOptionGreeks(instrumentKeyCsv));
        } catch (Exception t) {
            log.error("getOptionGreeks failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Market depth / order book for an instrument (if your UpstoxService exposes it).
     */
    public Result<List<OrderBookResponse>> getOrderBook() {
        try {
            return Result.ok(upstox.getOrderBook());
        } catch (Exception t) {
            log.error("getOrderBook failed", t);
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
            OHLC_Quotes q = upstoxService.getMarketOHLCQuote(instrumentKey, "I1");
            OHLC_Quotes.OHLCData d = (q == null || q.getData() == null) ? null : q.getData().get(instrumentKey);
            if (d == null || d.getLive_ohlc() == null) return true; // can't evaluate → allow
            double hi = d.getLive_ohlc().getHigh();
            double lo = d.getLive_ohlc().getLow();
            double cl = d.getLive_ohlc().getClose();
            if (cl <= 0) return true; // allow if we can't compute properly
            double spread = (hi - lo) / cl;
            return BigDecimal.valueOf(spread).compareTo(maxSpreadPct) <= 0;
        } catch (Exception t) {
            return true; // be permissive if preflight fails
        }
    }


    public Result<PlaceOrderResponse> placeTargetOrder(String instrumentKey, int qty, BigDecimal targetPrice) {
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

    public Result<PlaceOrderResponse> placeStopLossOrder(String instrumentKey, int qty, BigDecimal triggerPrice) {
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

    public Result<ModifyOrderResponse> amendOrderPrice(String orderId, BigDecimal newPrice) {
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
            OHLC_Quotes ohlc = upstoxService.getMarketOHLCQuote(instrumentKey, "1minute");
            OHLC_Quotes.OHLCData d = (ohlc == null || ohlc.getData() == null) ? null : ohlc.getData().get(instrumentKey);
            if (d != null && d.getLive_ohlc() != null) {
                double hi = d.getLive_ohlc().getHigh();
                double lo = d.getLive_ohlc().getLow();
                if (hi > 0 && lo > 0) {
                    return Optional.of(BigDecimal.valueOf((hi + lo) / 2.0).setScale(2, RoundingMode.HALF_UP));
                }
            }

            // Fallback: LTP
            LTP_Quotes ltp = upstoxService.getMarketLTPQuote(instrumentKey);
            double px = (ltp != null && ltp.getData() != null && ltp.getData().get(instrumentKey) != null)
                    ? ltp.getData().get(instrumentKey).getLast_price() : 0.0;
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
            OHLC_Quotes ohlc = upstoxService.getMarketOHLCQuote(instrumentKey, "I1");
            OHLC_Quotes.OHLCData d = (ohlc == null || ohlc.getData() == null) ? null : ohlc.getData().get(instrumentKey);
            if (d == null || d.getLive_ohlc() == null) return Optional.empty();
            double hi = d.getLive_ohlc().getHigh();
            double lo = d.getLive_ohlc().getLow();
            double cl = d.getLive_ohlc().getClose();
            if (hi <= 0 || lo <= 0 || cl <= 0) return Optional.empty();
            double pct = (hi - lo) / cl;
            return Optional.of(BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP));
        } catch (Exception t) {
            return Optional.empty();
        }
    }
}
