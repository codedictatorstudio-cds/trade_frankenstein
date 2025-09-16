package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.upstox.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

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

    // =========================================================================
    // WRITE ops (gated by market hours) --------------------------------------
    // =========================================================================

    /**
     * Place an order; market-hours gated unless AMO=true or testMode=true.
     */
    public Result<PlaceOrderResponse> placeOrder(PlaceOrderRequest req) {
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

            if (!tradeMode.isSandBox() && !isAmo(req) && !isMarketOpenNowIst()) {
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
            } catch (Throwable ignored) {
            }
            try {
                stream.send("order.placed", placed);
            } catch (Throwable ignored) {
            }

            return Result.ok(placed);
        } catch (Throwable t) {
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
            if (!tradeMode.isSandBox() && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for modifying orders");
            }
            ModifyOrderResponse r = upstox.modifyOrder(req);
            try {
                stream.send("order.modified", r);
            } catch (Throwable ignored) {
            }
            return Result.ok(r);
        } catch (Throwable t) {
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
            if (!tradeMode.isSandBox() && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for cancelling orders");
            }
            CancelOrderResponse r = upstox.cancelOrder(orderId);
            try {
                stream.send("order.cancelled", r);
            } catch (Throwable ignored) {
            }
            return Result.ok(r);
        } catch (Throwable t) {
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
        } catch (Throwable t) {
            log.error("getOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<OrderHistoryResponse> listOrders(String order_id, String tag) {
        try {
            return Result.ok(upstox.getOrderHistory(order_id, tag));
        } catch (Throwable t) {
            log.error("listOrders failed", t);
            return Result.fail(t);
        }
    }

    public Result<OrderTradesResponse> getOrderTrades() {
        try {
            return Result.ok(upstox.getOrderTrades());
        } catch (Throwable t) {
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
        } catch (Throwable t) {
            log.error("getOrderTrades failed", t);
            return Result.fail(t);
        }
    }

    public Result<PortfolioResponse> getPortfolio() {
        try {
            return Result.ok(upstox.getShortTermPositions());
        } catch (Throwable t) {
            log.error("getPortfolio failed", t);
            return Result.fail(t);
        }
    }

    public Result<HoldingsResponse> getHoldings() {
        try {
            return Result.ok(upstox.getLongTermHoldings());
        } catch (Throwable t) {
            log.error("getHoldings failed", t);
            return Result.fail(t);
        }
    }

    public Result<FundsResponse> getFunds(String segment) {
        try {
            return Result.ok(upstox.getFundAndMargin(segment));
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
        } catch (Throwable t) {
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
     * Try to detect AMO flag on the request without tying to a specific getter name.
     */
    private static boolean isAmo(PlaceOrderRequest req) {
        try {
            // Lombok on "is_amo" often generates setIs_amo/getIs_amo; sometimes boolean -> isIs_amo.
            try {
                java.lang.reflect.Method m = PlaceOrderRequest.class.getMethod("isIs_amo");
                Object v = m.invoke(req);
                if (v instanceof Boolean) return ((Boolean) v).booleanValue();
            } catch (NoSuchMethodException ignored) {
            }
            try {
                java.lang.reflect.Method m = PlaceOrderRequest.class.getMethod("getIs_amo");
                Object v = m.invoke(req);
                if (v instanceof Boolean) return ((Boolean) v).booleanValue();
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
            log.info("isAmo check failed", ignored.getLocalizedMessage());
        }
        return false;
    }
}
