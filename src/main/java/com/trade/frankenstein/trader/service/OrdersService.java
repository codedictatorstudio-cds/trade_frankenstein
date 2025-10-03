package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
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

    private final String defaultProduct = "MIS";
    private final String defaultValidity = "DAY";
    private final int workingTtlMinutes = 120;
    /**
     * Minimal in-memory tracker so isOrderWorking() has a fallback even if broker status isn't queried here.
     */
    private final Map<String, Instant> workingOrders = new ConcurrentHashMap<>();

    @Autowired
    private UpstoxService upstox;
    @Autowired
    private RiskService risk;
    @Autowired
    private FastStateStore fast; // Step-3: idempotency / rate counters
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private EventPublisher events;

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

    /**
     * Build an idempotency hash from common request fields; fall back to req.toString() if needed.
     */
    private static String hashDraftSafe(PlaceOrderRequest req) {
        String s;
        try {
            String k = String.valueOf(req.getInstrumentToken());
            String tt = String.valueOf(req.getTransactionType());
            String ot = String.valueOf(req.getOrderType());
            String pr = String.valueOf(req.getPrice());
            String tq = String.valueOf(req.getQuantity());
            s = k + "|" + tt + "|" + ot + "|" + pr + "|" + tq;
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

    // =====================================================================
    // READ PATHS
    // =====================================================================

    /**
     * Place an order.
     * Market-hours rule (live): allowed if (marketOpen) OR (AMO).
     */
    public Result<PlaceOrderResponse> placeOrder(PlaceOrderRequest req) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        applyOrderDefaults(req);
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

            // Step-3: idempotency (120s) — prevent accidental duplicates
            String idemKey = "order:idemp:" + hashDraftSafe(req);
            boolean first = fast.setIfAbsent(idemKey, "1", Duration.ofSeconds(workingTtlMinutes));
            if (!first) {
                return Result.fail("DUPLICATE", "Duplicate order (idempotency window 120s)");
            }


            // Step-9 Flags (feature gates) — additional time-of-day and execution behaviors
            Result<Void> guard = risk.checkOrder(req);
            if (guard == null || !guard.isOk()) {
                return Result.fail(guard == null ? "RISK_ERROR" : guard.getErrorCode(),
                        guard == null ? "Risk check failed" : guard.getError());
            }

            // Enforce market hours unless AMO
            if (!Boolean.TRUE.equals(req.isIsAmo()) && !isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing orders");
            }

            PlaceOrderResponse placed = upstox.placeOrder(req);
            if (placed != null && placed.getData() != null && placed.getData().getOrderId() != null) {
                markWorking(placed.getData().getOrderId());
            }
            // Side-effects
            try {
                risk.noteOrderPlaced();
            } catch (Exception ex) {
                log.warn("risk.noteOrderPlaced failed", ex);
            }
            try {
                publishOrderEvent("order.placed", placed);
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
     */
    public Result<ModifyOrderResponse> modifyOrder(ModifyOrderRequest req) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (req == null) return Result.fail("BAD_REQUEST", "ModifyOrderRequest is required");

            if (!isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for modifying orders");
            }
            ModifyOrderResponse r = upstox.modifyOrder(req);
            try {
                publishOrderEvent("order.modified", r);
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
     */
    public Result<CancelOrderResponse> cancelOrder(String orderId) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (isBlank(orderId)) return Result.fail("BAD_REQUEST", "orderId is required");

            if (!isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for cancelling orders");
            }
            CancelOrderResponse r = upstox.cancelOrder(orderId);
            try {
                publishOrderEvent("order.cancelled", r);
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
    // Helpers (price/guards) – used by StrategyService & EngineService
    // =====================================================================

    public Result<GetOrderDetailsResponse> getOrder(String orderId) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (isBlank(orderId)) return Result.fail("BAD_REQUEST", "orderId is required");
            return Result.ok(upstox.getOrderDetails(orderId));
        } catch (Exception t) {
            log.error("getOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<GetOrderResponse> listOrders(String orderId, String tag) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
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
    // Convenience order helpers (limit/SL) – used by EngineService
    // =====================================================================

    /**
     * Guard: abort if live spread is too wide.
     * Uses true bid/ask when available; otherwise (H-L)/Close from I1 bar.
     */
    public boolean preflightSlippageGuard(String instrumentKey, BigDecimal maxSpreadPct) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return true;
        }

        try {
            // 1) Prefer depth
            Optional<UpstoxService.BestBidAsk> oba = upstox.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid(), ask = oba.get().ask();
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return java.util.Optional.empty();
        }

        try {
            // Depth
            Optional<UpstoxService.BestBidAsk> oba = upstox.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid(), ask = oba.get().ask();
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return java.util.Optional.empty();
        }

        try {
            // Depth
            Optional<UpstoxService.BestBidAsk> oba = upstox.getBestBidAsk(instrumentKey);
            if (oba.isPresent()) {
                double bid = oba.get().bid(), ask = oba.get().ask();
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
    // Internals
    // =====================================================================

    public Result<PlaceOrderResponse> placeTargetOrder(String instrumentKey, int qty, Float targetPrice) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            // Step-9 Flags gate for new openings
            if (isBlank(instrumentKey) || qty <= 0 || targetPrice == null) {
                return Result.fail("BAD_REQUEST", "instrumentKey/qty/price are required");
            }
            if (!isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing target orders");
            }
            PlaceOrderResponse r = upstox.placeTargetOrder(instrumentKey, qty, targetPrice);
            try {
                publishOrderEvent("order.placed", r);
            } catch (Exception ex) {
                log.warn("stream send failed", ex);
            }
            try {
                risk.noteOrderPlaced();
            } catch (Exception ignore) {
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("placeTargetOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<PlaceOrderResponse> placeStopLossOrder(String instrumentKey, int qty, Float triggerPrice) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (isBlank(instrumentKey) || qty <= 0 || triggerPrice == null) {
                return Result.fail("BAD_REQUEST", "instrumentKey/qty/trigger are required");
            }
            if (!isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for placing stop-loss orders");
            }
            PlaceOrderResponse r = upstox.placeStopLossOrder(instrumentKey, qty, triggerPrice);
            try {
                publishOrderEvent("order.placed", r);
            } catch (Exception ex) {
                log.warn("stream send failed", ex);
            }
            try {
                risk.noteOrderPlaced();
            } catch (Exception ignore) {
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("placeStopLossOrder failed", t);
            return Result.fail(t);
        }
    }

    public Result<ModifyOrderResponse> amendOrderPrice(String orderId, Float newPrice) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (orderId == null || orderId.trim().isEmpty() || newPrice == null) {
                return Result.fail("BAD_REQUEST", "orderId/newPrice are required");
            }
            if (!isMarketOpenNowIst()) {
                return Result.fail("MARKET_CLOSED", "Market is closed for modifying orders");
            }
            ModifyOrderResponse r = upstox.amendOrderPrice(orderId, newPrice);
            try {
                publishOrderEvent("order.modified", r);
            } catch (Exception ignored) {
                log.error("stream send failed", ignored);
            }
            return Result.ok(r);
        } catch (Exception t) {
            log.error("amendOrderPrice failed", t);
            return Result.fail(t);
        }
    }

    private void markWorking(String orderId) {
        if (orderId != null) workingOrders.put(orderId, Instant.now());
    }


    // --- Step-10 additive methods ---

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String asIso(java.time.Instant t) {
        return t == null ? null : t.toString();
    }


    private PlaceOrderRequest applyOrderDefaults(PlaceOrderRequest intent) {
        if (intent == null) return null;
        if (intent.getProduct() == null || intent.getProduct().getValue().isEmpty()) {
            intent = intent.product(PlaceOrderRequest.ProductEnum.valueOf(defaultProduct));
        }
        if (intent.getValidity() == null || intent.getValidity().getValue().isEmpty()) {
            intent = intent.validity(PlaceOrderRequest.ValidityEnum.valueOf(defaultValidity));
        }
        return intent;
    }

    private void publishOrderEvent(String event, Object o) {
        try {
            // Convert to JsonNode and wrap in kafkaesque envelope
            final com.fasterxml.jackson.databind.node.ObjectNode wrap = mapper.createObjectNode();
            final java.time.Instant now = java.time.Instant.now();
            wrap.put("ts", now.toEpochMilli());
            wrap.put("ts_iso", now.toString());
            wrap.put("event", event);
            wrap.put("source", "orders");

            com.fasterxml.jackson.databind.JsonNode data = (o instanceof com.fasterxml.jackson.databind.JsonNode)
                    ? (com.fasterxml.jackson.databind.JsonNode) o
                    : mapper.valueToTree(o);
            if (data != null) wrap.set("data", data);

            // Best-effort key selection: symbol -> instrument_token/orderId -> id -> event tail
            String key = null;
            try {
                if (data != null && data.hasNonNull("symbol")) key = data.get("symbol").asText();
            } catch (Throwable ignore) {
            }
            if (key == null) {
                try {
                    if (data != null && data.hasNonNull("instrument_key")) key = data.get("instrument_key").asText();
                } catch (Throwable ignore) {
                }
            }
            if (key == null) {
                try {
                    if (data != null && data.hasNonNull("instrumentToken")) key = data.get("instrumentToken").asText();
                } catch (Throwable ignore) {
                }
            }
            if (key == null) {
                try {
                    if (data != null && data.hasNonNull("orderId")) key = data.get("orderId").asText();
                } catch (Throwable ignore) {
                }
            }
            if (key == null) {
                try {
                    if (data != null && data.hasNonNull("id")) key = data.get("id").asText();
                } catch (Throwable ignore) {
                }
            }
            if (key == null) {
                // Fallback to event tail ("placed"/"modified"/"cancelled")
                String[] sub = event == null ? null : event.split("\\.");
                key = (sub != null && sub.length > 1) ? sub[1] : "order";
            }

            events.publish(EventBusConfig.TOPIC_ORDER, key, wrap.toString());
        } catch (Exception e) {
            log.warn("best-effort publishOrderEvent failed: {}", e.toString());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
