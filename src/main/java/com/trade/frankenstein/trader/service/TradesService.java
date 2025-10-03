package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import com.upstox.api.GetTradeResponse;
import com.upstox.api.TradeData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class TradesService {

    // Fallback in-memory idempotency (per-process) for SL events
    private final ConcurrentMap<String, Long> slOnceKeys = new ConcurrentHashMap<>();
    @Autowired
    private UpstoxService upstoxService;
    @Autowired
    private TradeRepo tradeRepo;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private EventPublisher events;
    @Autowired
    private ObjectMapper mapper;
    // ----------------------------------------------------------------------------
    // Queries (return Trade documents directly from our DB)
    // ----------------------------------------------------------------------------
    @Autowired(required = false)
    private FastStateStore fast; // optional
    @Autowired(required = false)
    private RiskService riskService; // optional wiring to RiskService

    private static String formatIst(Instant ts) {
        if (ts == null) return null;
        return DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH)
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(ts);
    }

    // ----------------------------------------------------------------------------
    // Why? / Explain (used by RecentTradesCard action)
    // ----------------------------------------------------------------------------

    private static String trimDouble(double d) {
        // simple compact formatting for prices/pnl
        return (Math.abs(d) >= 1000)
                ? String.format(Locale.ENGLISH, "%.0f", d)
                : String.format(Locale.ENGLISH, "%.2f", d);
    }

    private static String inferLegCEorPE(Trade t) {
        String sym = safeNull(t.getSymbol());
        if (sym == null) return null;

        String u = sym.toUpperCase(Locale.ROOT).trim();

        // Human symbol clues
        if (u.endsWith(" CE") || u.contains(" CE ")) return "CE";
        if (u.endsWith(" PE") || u.contains(" PE ")) return "PE";

        // Upstox instrument key: ...|...|...|...|CE| or ...|PE
        if (u.contains("|")) {
            String[] parts = u.split("\\|");
            if (parts.length >= 2) {
                String last = parts[parts.length - 1].trim();
                if ("CE".equals(last)) return "CE";
                if ("PE".equals(last)) return "PE";
            }
        }

        return null;
    }

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    // ----------------------------------------------------------------------------
    // Reconcile from Upstox (today) — REAL-TIME: read-only of broker state
    // ----------------------------------------------------------------------------

    private static String safeNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ----------------------------------------------------------------------------
    // Repo helpers
    // ----------------------------------------------------------------------------

    private static boolean safeNullEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ----------------------------------------------------------------------------
    // Throttle signal for StrategyService: what exposure is already open?
    // ----------------------------------------------------------------------------

    private static OrderSide nzSide(OrderSide s) {
        return s == null ? OrderSide.BUY : s;
    }

    private static TradeStatus nzs(TradeStatus s) {
        return s == null ? TradeStatus.PENDING : s;
    }

    // ----------------------------------------------------------------------------
    // General helpers
    // ----------------------------------------------------------------------------

    private static OrderSide parseSide(String txType) {
        if (txType == null) return OrderSide.BUY;
        String s = txType.trim().toUpperCase(Locale.ROOT);
        return "SELL".equals(s) ? OrderSide.SELL : OrderSide.BUY;
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null) {
                String t = s.trim();
                if (!t.isEmpty()) return t;
            }
        }
        return null;
    }

    private static Instant parseInstant(String ts) {
        if (ts == null) return null;
        try {
            return Instant.parse(ts); // expects 'Z'
        } catch (DateTimeParseException e1) {
            try {
                return OffsetDateTime.parse(ts).toInstant(); // handles +05:30 etc.
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    // ----------------------------------------------------------------------------
    // Stop-Loss (SL) recording — unambiguous & exactly-once per SL
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String asIso(Instant t) {
        return t == null ? null : java.time.format.DateTimeFormatter.ISO_INSTANT.format(t);
    }

    @Transactional(readOnly = true)
    public Result<Iterable<Trade>> listRecent(int limit) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        int lim = (limit <= 0 ? 20 : Math.min(limit, 200));
        Page<Trade> page = tradeRepo.findAll(
                PageRequest.of(0, lim, Sort.by(
                        Sort.Order.desc("exitTime"),
                        Sort.Order.desc("entryTime"),
                        Sort.Order.desc("updatedAt"),
                        Sort.Order.desc("createdAt")))
        );
        return Result.ok(page.getContent());
    }

    @Transactional(readOnly = true)
    public Result<Iterable<Trade>> listPaged(int page, int size,
                                             @Nullable TradeStatus status,
                                             @Nullable OrderSide side,
                                             @Nullable String symbolContains) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);

        Page<Trade> data = tradeRepo.findAll(PageRequest.of(p, s, Sort.by(
                Sort.Order.desc("exitTime"),
                Sort.Order.desc("entryTime"),
                Sort.Order.desc("updatedAt"),
                Sort.Order.desc("createdAt"))));

        List<Trade> out = new ArrayList<Trade>(data.getNumberOfElements());
        for (Trade t : data.getContent()) {
            if (status != null && status != nzs(t.getStatus())) continue;
            if (side != null && side != nzSide(t.getSide())) continue;
            if (symbolContains != null) {
                String sym = safe(t.getSymbol());
                if (!sym.toLowerCase(Locale.ROOT).contains(symbolContains.toLowerCase(Locale.ROOT))) continue;
            }
            out.add(t);
        }
        return Result.ok(out);
    }

    @Transactional(readOnly = true)
    public Result<Trade> get(String tradeId) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (isBlank(tradeId)) return Result.fail("BAD_REQUEST", "tradeId is required");
        Optional<Trade> t = tradeRepo.findById(tradeId);
        return t.map(Result::ok)
                .orElseGet(new java.util.function.Supplier<Result<Trade>>() {
                    @Override
                    public Result<Trade> get() {
                        return Result.fail("NOT_FOUND", "Trade not found: " + tradeId);
                    }
                });
    }

    @Transactional(readOnly = true)
    public Result<String> explain(String tradeId) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        if (isBlank(tradeId)) return Result.fail("BAD_REQUEST", "tradeId is required");
        Optional<Trade> tOpt = tradeRepo.findById(tradeId);
        if (!tOpt.isPresent()) return Result.fail("NOT_FOUND", "Trade not found: " + tradeId);

        Trade t = tOpt.get();
        if (t.getExplain() != null && !t.getExplain().trim().isEmpty()) {
            // Preserve explicitly stored human reason if present
            return Result.ok(t.getExplain());
        }

        // Compose a concise, deterministic fallback explanation
        String sym = safe(t.getSymbol());
        String side = (t.getSide() == null ? "—" : t.getSide().name());
        Integer qty = (t.getQuantity() == null ? 0 : t.getQuantity());
        double entry = t.getEntryPrice();
        Double cur = (t.getCurrentPrice() == 0.0 ? null : t.getCurrentPrice());

        String entryTs = formatIst(t.getEntryTime());
        String exitTs = formatIst(t.getExitTime());

        StringBuilder sb = new StringBuilder(128);
        sb.append(sym).append(" • ").append(side)
                .append(" • qty ").append(qty)
                .append(" • @ ").append(trimDouble(entry));
        if (entryTs != null) sb.append(" • entered ").append(entryTs);

        if (t.getExitTime() != null) {
            sb.append(" • exited ").append(exitTs)
                    .append(" • pnl ").append(trimDouble(t.getPnl()));
        } else if (cur != null) {
            double pnl = (t.getSide() == OrderSide.SELL)
                    ? (entry - cur) * qty
                    : (cur - entry) * qty;
            sb.append(" • mark ").append(trimDouble(cur))
                    .append(" • mtm ").append(trimDouble(pnl));
        }

        String ord = safeNull(t.getOrder_id());
        String brk = safeNull(t.getBrokerTradeId());
        if (brk != null || ord != null) {
            sb.append(" • ").append("src: ");
            if (brk != null) sb.append("tradeId=").append(brk);
            if (brk != null && ord != null) sb.append(", ");
            if (ord != null) sb.append("orderId=").append(ord);
        }

        return Result.ok(sb.toString());
    }

    @Scheduled(fixedDelayString = "${trade.trades.reconcile-ms:45000}")
    @Transactional
    public void reconcileToday() {
        if (!isLoggedIn()) return;
        GetTradeResponse resp;
        try {
            resp = upstoxService.getTradesForDay();
        } catch (Exception t) {
            log.error("reconcileToday: getTradesForDay failed: {}", t.toString());
            return;
        }
        if (resp == null || resp.getData() == null || resp.getData().isEmpty()) return;

        for (TradeData td : resp.getData()) {
            String brokerTradeId = safeNull(td.getTradeId());
            if (isBlank(brokerTradeId)) continue;

            // ---- Update existing if present ----
            try {
                Optional<Trade> existingOpt = findByBrokerTradeId(brokerTradeId);
                if (existingOpt.isPresent()) {
                    Trade existing = existingOpt.get();
                    boolean changed = false;

                    Integer newQty = td.getQuantity();
                    if (!Objects.equals(existing.getQuantity(), newQty)) {
                        existing.setQuantity(newQty);
                        changed = true;
                    }

                    double newPrice = td.getAveragePrice();
                    if (Double.compare(existing.getEntryPrice(), newPrice) != 0) {
                        existing.setEntryPrice(newPrice);
                        changed = true;
                    }

                    String sym = firstNonBlank(td.getTradingsymbol(), td.getInstrumentToken(), existing.getSymbol());
                    if (!safe(sym).equals(safe(existing.getSymbol()))) {
                        existing.setSymbol(sym);
                        changed = true;
                    }

                    String ordId = safeNull(td.getOrderId());
                    if (!safeNullEquals(existing.getOrder_id(), ordId)) {
                        existing.setOrder_id(ordId);
                        changed = true;
                    }

                    String txType = td.getTransactionType() != null ? safeNull(td.getTransactionType().getValue()) : null;
                    OrderSide side = parseSide(txType);
                    if (existing.getSide() != side) {
                        existing.setSide(side);
                        changed = true;
                    }

                    Instant entryTs = parseInstant(safeNull(td.getOrderTimestamp()));
                    if (!Objects.equals(existing.getEntryTime(), entryTs)) {
                        existing.setEntryTime(entryTs);
                        changed = true;
                    }

                    if (changed) {
                        existing.setUpdatedAt(Instant.now());
                        Trade saved = tradeRepo.save(existing);
                        try {
                            stream.publishTrade("trade.updated", saved);
                            publishTradeEvent("trade.updated", saved);
                        } catch (Exception ignored) {
                            log.error("reconcileToday: stream send trade.updated failed", ignored);
                        }
                    }
                    continue;
                }
            } catch (Exception ignored) {
                log.error("reconcileToday: update existing trade failed", ignored);
            }

            // ---- New trade create (idempotent via FastStateStore daily key) ----
            if (fast != null) {
                String idemKey = "trade:seen:" + brokerTradeId;
                boolean first = false;
                try {
                    first = fast.setIfAbsent(idemKey, "1", Duration.ofHours(18));
                } catch (Throwable ignore) { /* non-fatal */ }
                if (!first) {
                    // already seen recently → skip duplicate create
                    continue;
                }
            }

            Trade t = mapFrom(td);
            Trade saved = tradeRepo.save(t);
            try {
                stream.publishTrade("trade.created", saved);
                publishTradeEvent("trade.created", saved);
            } catch (Exception ignored) {
                log.error("reconcileToday: stream send trade.created failed", ignored);
            }
        }
    }

    private Trade mapFrom(TradeData td) {
        String sym = firstNonBlank(td.getTradingsymbol(), td.getInstrumentToken(), "—");
        String txType = td.getTransactionType() != null ? safeNull(td.getTransactionType().getValue()) : null;
        OrderSide side = parseSide(txType);
        Integer qty = td.getQuantity();
        double avgPrice = td.getAveragePrice();
        Instant ts = parseInstant(safeNull(td.getOrderTimestamp()));

        Trade t = Trade.builder()
                .id(null) // Mongo will assign
                .order_id(safeNull(td.getOrderId()))
                .brokerTradeId(safeNull(td.getTradeId()))
                .symbol(sym) // may be human symbol OR instrument key; we can store both later if needed
                .side(side)
                .quantity(qty)
                .entryPrice(avgPrice)
                .currentPrice(avgPrice) // initial = entry; updated later by mark-to-market flow
                .pnl(0.0)
                .status(TradeStatus.FILLED)
                .entryTime(ts)
                .exitTime(null)
                .durationMs(null)
                .explain(null)
                .build();
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return t;
    }

    private Optional<Trade> findByBrokerTradeId(String brokerTradeId) {
        return tradeRepo.findByBrokerTradeId(brokerTradeId);
    }

    private ObjectNode toJsonTrade(Trade t, String event) {
        final ObjectNode b = mapper.createObjectNode();

        java.time.Instant _now = java.time.Instant.now();
        b.put("ts", _now.toEpochMilli());
        b.put("ts_iso", _now.toString());
        b.put("source", "trade");
// strings & enums
        b.put("event", nz(event));
        b.put("tradeId", nz(t.getId()));
        b.put("brokerTradeId", nz(t.getBrokerTradeId()));
        b.put("orderId", nz(t.getOrder_id()));
        b.put("symbol", nz(t.getSymbol()));
        b.put("side", t.getSide() == null ? "" : t.getSide().name());

        // numbers
        b.put("quantity", t.getQuantity() == null ? 0 : t.getQuantity().intValue());
        if (t.getEntryPrice() != null) b.put("entryPrice", t.getEntryPrice());
        else b.putNull("entryPrice");
        if (t.getCurrentPrice() != null) b.put("currentPrice", t.getCurrentPrice());
        else b.putNull("currentPrice");
        if (t.getPnl() != null) b.put("pnl", t.getPnl());
        else b.putNull("pnl");

        // status/time
        b.put("status", t.getStatus() == null ? "" : t.getStatus().name());
        if (t.getEntryTime() != null) b.put("entryTime", asIso(t.getEntryTime()));
        if (t.getExitTime() != null) b.put("exitTime", asIso(t.getExitTime()));
        if (t.getUpdatedAt() != null) b.put("updatedAt", asIso(t.getUpdatedAt()));
        if (t.getCreatedAt() != null) b.put("createdAt", asIso(t.getCreatedAt()));

        return b;
    }

    private void publishTradeEvent(String eventName, Trade t) {
        try {
            ObjectNode payload = toJsonTrade(t, eventName);
            String key = nz(t.getSymbol());
            events.publish(EventBusConfig.TOPIC_TRADE, key, payload.toString());
        } catch (Throwable ignored) { /* best-effort */ }
    }

    private void publishStopLossEvent(String instrumentKey, String slEventKey) {
        try {
            final ObjectNode b = mapper.createObjectNode()
                    .put("event", "trade.stoploss")
                    .put("source", "trade")
                    .put("ts", java.time.Instant.now().toEpochMilli())
                    .put("ts_iso", java.time.Instant.now().toString())
                    .put("instrumentKey", nz(instrumentKey))
                    .put("slEventKey", nz(slEventKey))
                    .put("ts", asIso(Instant.now()));

            final String key = instrumentKey == null ? "" : instrumentKey;
            events.publish(EventBusConfig.TOPIC_TRADE, key, mapper.writeValueAsString(b));
        } catch (Throwable ignored) { /* best-effort */ }
    }

    // ----------------------------------------------------------------------------

    /**
     * Returns HAVE_CALL / HAVE_PUT / NONE based on open trades.
     * Open criteria:
     * - exitTime == null AND status not in {CLOSED, EXITED, CANCELLED, REJECTED, FAILED}
     * CE/PE inference:
     * - from human symbol suffix " CE"/" PE"
     * - or from Upstox key last segment (e.g., ...|CE or ...|PE)
     */
    public Optional<StrategyService.PortfolioSide> getOpenPortfolioSide() {
        if (!isLoggedIn()) return Optional.of(StrategyService.PortfolioSide.NONE);
        try {
            Page<Trade> page = tradeRepo.findAll(
                    PageRequest.of(0, 200, Sort.by(
                            Sort.Order.desc("updatedAt"),
                            Sort.Order.desc("createdAt"),
                            Sort.Order.desc("entryTime")))
            );

            boolean hasCE = false, hasPE = false;

            for (Trade t : page.getContent()) {
                if (t == null) continue;

                // Determine if still open
                TradeStatus st = t.getStatus();
                String s = (st == null ? "" : st.name());
                boolean looksClosed = s.equalsIgnoreCase("CLOSED")
                        || s.equalsIgnoreCase("EXITED")
                        || s.equalsIgnoreCase("CANCELLED")
                        || s.equalsIgnoreCase("REJECTED")
                        || s.equalsIgnoreCase("FAILED");
                boolean isOpen = (t.getExitTime() == null) && !looksClosed;

                if (!isOpen) continue;

                String leg = inferLegCEorPE(t);
                if ("CE".equals(leg)) hasCE = true;
                else if ("PE".equals(leg)) hasPE = true;

                if (hasCE && hasPE) {
                    // Both present → treat as NONE for throttle (don’t force a hedge)
                    return Optional.of(StrategyService.PortfolioSide.NONE);
                }
            }

            if (hasCE && !hasPE) return Optional.of(StrategyService.PortfolioSide.HAVE_CALL);
            if (hasPE && !hasCE) return Optional.of(StrategyService.PortfolioSide.HAVE_PUT);
            return Optional.of(StrategyService.PortfolioSide.NONE);
        } catch (Exception t) {
            return Optional.of(StrategyService.PortfolioSide.NONE);
        }
    }

    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Public hook: call when an EXIT fill is confirmed for a Stop-Loss order.
     * Ensures RiskService.recordStopLoss(...) is invoked exactly once per unique SL event.
     * Idempotency key is derived from orderId.
     *
     * @param instrumentKey Upstox instrument key (preferred) for the exited position
     * @param orderId       Broker order id for the SL exit (unique per SL)
     */
    public void noteStopLossByOrder(String instrumentKey, String orderId) {
        if (!isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        if (isBlank(instrumentKey) || isBlank(orderId)) return;
        noteStopLossInternal(instrumentKey, "ord:" + orderId);
    }

    /**
     * Public hook: call when an EXIT trade (fill) confirms a Stop-Loss.
     * Ensures RiskService.recordStopLoss(...) is invoked exactly once per unique SL event.
     * Idempotency key is derived from broker trade id.
     *
     * @param instrumentKey Upstox instrument key (preferred)
     * @param brokerTradeId Broker trade id for the SL exit
     */
    public void noteStopLossByTrade(String instrumentKey, String brokerTradeId) {
        if (!isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        if (isBlank(instrumentKey) || isBlank(brokerTradeId)) return;
        noteStopLossInternal(instrumentKey, "trd:" + brokerTradeId);
    }

    // Core idempotent SL handler
    private void noteStopLossInternal(String instrumentKey, String slEventKey) {
        try {
            // Process-wide fast path dedupe (ConcurrentMap)
            long now = java.time.Instant.now().getEpochSecond();
            Long prev = slOnceKeys.putIfAbsent(slEventKey, now);
            if (prev != null) {
                // already processed in this process
                return;
            }

            // Cross-process dedupe if FastStateStore available
            if (fast != null) {
                try {
                    boolean first = fast.setIfAbsent("sl:seen:" + slEventKey, "1", java.time.Duration.ofHours(18));
                    if (!first) {
                        return; // already handled elsewhere
                    }
                } catch (Throwable t) {
                    // best-effort; continue to record SL even if Redis unavailable
                    log.warn("noteStopLossInternal: fast.setIfAbsent failed: {}", t.toString());
                }
            }

            // Finally record the SL once
            try {
                if (riskService != null) {
                    riskService.recordStopLoss(instrumentKey);
                } else {
                    log.warn("noteStopLossInternal: RiskService not wired; skipping recordStopLoss for {}", instrumentKey);
                }
            } catch (Throwable t) {
                log.error("noteStopLossInternal: recordStopLoss failed: {}", t.toString());
            }
            try {
                publishStopLossEvent(instrumentKey, slEventKey);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log.error("noteStopLossInternal: unexpected error: {}", t.toString());
        }
    }

    /**
     * Housekeeping for in-memory idempotency map.
     * Runs hourly and trims keys older than 12h to cap memory.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    public void pruneOldSlOnceKeys() {
        if (!isLoggedIn()) {
            return;
        }
        long cutoff = Instant.now().minus(Duration.ofHours(12)).getEpochSecond();
        try {
            Iterator<Map.Entry<String, Long>> it = slOnceKeys.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                Long when = e.getValue();
                if (when == null || when.longValue() < cutoff) {
                    it.remove();
                }
            }
        } catch (Throwable ignored) { /* best-effort */ }
    }
}