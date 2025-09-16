package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.model.upstox.OrderTradesResponse;
import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradesService {

    private final UpstoxService upstoxService;
    private final TradeRepo tradeRepo;
    private final StreamGateway stream;

    // ------------------------------------------------------------------------------
    // Queries (return Trade documents directly)
    // ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Result<Iterable<Trade>> listRecent(int limit) {
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
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);

        Page<Trade> data = tradeRepo.findAll(PageRequest.of(p, s, Sort.by(
                Sort.Order.desc("exitTime"),
                Sort.Order.desc("entryTime"),
                Sort.Order.desc("updatedAt"),
                Sort.Order.desc("createdAt"))));

        java.util.List<Trade> out = new java.util.ArrayList<Trade>(data.getNumberOfElements());
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
        if (isBlank(tradeId)) return Result.fail("BAD_REQUEST", "tradeId is required");
        Optional<Trade> t = tradeRepo.findById(tradeId);
        return t.map(Result::ok)
                .orElseGet(() -> Result.fail("NOT_FOUND", "Trade not found: " + tradeId));
    }

    // ------------------------------------------------------------------------------
    // Reconcile from Upstox (today) — REAL-TIME: no testMode / no market-hours gate
    // ------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${trade.trades.reconcile-ms:45000}")
    @Transactional
    public void reconcileToday() {
        OrderTradesResponse resp;
        try {
            resp = upstoxService.getTradesForDay();
        } catch (Throwable t) {
            log.info("reconcileToday: getTradesForDay failed: {}", t.getMessage());
            return;
        }
        if (resp == null || resp.getData() == null || resp.getData().isEmpty()) return;

        for (OrderTradesResponse.TradeData td : resp.getData()) {
            String brokerTradeId = safeNull(td.getTrade_id());
            if (isBlank(brokerTradeId)) continue;

            // Update existing
            try {
                Optional<Trade> existingOpt = findByBrokerTradeId(brokerTradeId);
                if (existingOpt.isPresent()) {
                    Trade existing = existingOpt.get();
                    boolean changed = false;

                    Integer newQty = Integer.valueOf(td.getQuantity());
                    if (!Objects.equals(existing.getQuantity(), newQty)) {
                        existing.setQuantity(newQty);
                        changed = true;
                    }

                    double newPrice = td.getAverage_price();
                    if (Double.compare(existing.getEntryPrice(), newPrice) != 0) {
                        existing.setEntryPrice(newPrice);
                        changed = true;
                    }

                    String sym = firstNonBlank(td.getTradingsymbol(), td.getInstrument_token(), existing.getSymbol());
                    if (!safe(sym).equals(safe(existing.getSymbol()))) {
                        existing.setSymbol(sym);
                        changed = true;
                    }

                    String ordId = safeNull(td.getOrder_id());
                    if (!safeNullEquals(existing.getOrder_id(), ordId)) {
                        existing.setOrder_id(ordId);
                        changed = true;
                    }

                    OrderSide side = parseSide(safeNull(td.getTransaction_type()));
                    if (existing.getSide() != side) {
                        existing.setSide(side);
                        changed = true;
                    }

                    Instant entryTs = parseInstant(safeNull(td.getOrder_timestamp()));
                    if (!Objects.equals(existing.getEntryTime(), entryTs)) {
                        existing.setEntryTime(entryTs);
                        changed = true;
                    }

                    if (changed) {
                        existing.setUpdatedAt(Instant.now());
                        Trade saved = tradeRepo.save(existing);
                        try {
                            stream.send("trade.updated", saved);
                        } catch (Throwable ignored) {
                        }
                    }
                    continue;
                }
            } catch (Throwable ignored) {
                // fall through to create
            }

            // Create new Trade doc
            Trade t = mapFrom(td);
            Trade saved = tradeRepo.save(t);
            try {
                stream.send("trade.created", saved);
            } catch (Throwable ignored) {
            }
        }
    }

    private Trade mapFrom(OrderTradesResponse.TradeData td) {
        String sym = firstNonBlank(td.getTradingsymbol(), td.getInstrument_token(), "—");
        OrderSide side = parseSide(safeNull(td.getTransaction_type()));
        Integer qty = Integer.valueOf(td.getQuantity());
        double avgPrice = td.getAverage_price();
        Instant ts = parseInstant(safeNull(td.getOrder_timestamp()));

        Trade t = Trade.builder()
                .id(null) // Mongo will assign
                .order_id(safeNull(td.getOrder_id()))
                .brokerTradeId(safeNull(td.getTrade_id()))
                .symbol(sym)
                .side(side)
                .quantity(qty)
                .entryPrice(avgPrice)
                .currentPrice(avgPrice) // initialize = entry; later a ticker can update
                .pnl(0.0)               // compute later when exit or mark-to-market
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

    // ------------------------------------------------------------------------------
    // Repo helpers (work even if custom finders aren’t defined)
    // ------------------------------------------------------------------------------

    private Optional<Trade> findByBrokerTradeId(String brokerTradeId) {
        try {
            java.lang.reflect.Method m = tradeRepo.getClass().getMethod("findByBrokerTradeId", String.class);
            Object r = m.invoke(tradeRepo, brokerTradeId);
            if (r instanceof Optional) return (Optional<Trade>) r;
        } catch (Throwable ignored) {
        }
        for (Trade t : tradeRepo.findAll()) {
            if (brokerTradeId.equals(t.getBrokerTradeId())) return Optional.of(t);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------
    // General helpers
    // ------------------------------------------------------------------------------

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    private static String safeNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean safeNullEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static OrderSide nzSide(OrderSide s) {
        return s == null ? OrderSide.BUY : s;
    }

    private static TradeStatus nzs(TradeStatus s) {
        return s == null ? TradeStatus.PENDING : s;
    }

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

    private static Instant parseInstant(String iso) {
        try {
            return (iso == null) ? null : Instant.parse(iso);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
