package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import com.upstox.api.GetTradeResponse;
import com.upstox.api.TradeData;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

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

        List<Trade> out = new ArrayList<>(data.getNumberOfElements());
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
    // Why? / Explain (used by RecentTradesCard action)
    // ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Result<String> explain(String tradeId) {
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

    private static String formatIst(Instant ts) {
        if (ts == null) return null;
        return DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH)
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(ts);
    }

    private static String trimDouble(double d) {
        // simple compact formatting for prices/pnl
        return (Math.abs(d) >= 1000)
                ? String.format(Locale.ENGLISH, "%.0f", d)
                : String.format(Locale.ENGLISH, "%.2f", d);
    }

    // ------------------------------------------------------------------------------
    // Reconcile from Upstox (today) — REAL-TIME: no testMode / no market-hours gate
    // ------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${trade.trades.reconcile-ms:45000}")
    @Transactional
    public void reconcileToday() {
        GetTradeResponse resp;
        try {
            resp = upstoxService.getTradesForDay();
        } catch (Exception t) {
            log.error("reconcileToday: getTradesForDay failed: {}", t);
            return;
        }
        if (resp == null || resp.getData() == null || resp.getData().isEmpty()) return;

        for (TradeData td : resp.getData()) {
            String brokerTradeId = safeNull(td.getTradeId());
            if (isBlank(brokerTradeId)) continue;

            // Update existing
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

                    OrderSide side = parseSide(safeNull(td.getTransactionType().getValue()));
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
                            stream.send("trade.updated", saved);
                        } catch (Exception ignored) {
                            log.error("reconcileToday: stream send trade.updated failed", ignored);
                        }
                    }
                    continue;
                }
            } catch (Exception ignored) {
                log.error("reconcileToday: update existing trade failed", ignored);
            }

            // Create new Trade doc
            Trade t = mapFrom(td);
            Trade saved = tradeRepo.save(t);
            try {
                stream.send("trade.created", saved);
            } catch (Exception ignored) {
                log.error("reconcileToday: stream send trade.created failed", ignored);
            }
        }
    }

    private Trade mapFrom(TradeData td) {
        String sym = firstNonBlank(td.getTradingsymbol(), td.getInstrumentToken(), "—");
        OrderSide side = parseSide(safeNull(td.getTransactionType().getValue()));
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

    // ------------------------------------------------------------------------------
    // Repo helpers
    // ------------------------------------------------------------------------------

    private Optional<Trade> findByBrokerTradeId(String brokerTradeId) {
        return tradeRepo.findByBrokerTradeId(brokerTradeId);
    }

    // ------------------------------------------------------------------------------
    // Throttle signal for StrategyService: what exposure is already open?
    // ------------------------------------------------------------------------------

    /**
     * Returns HAVE_CALL / HAVE_PUT / NONE based on open trades.
     * Open criteria:
     * - exitTime == null AND status not in {CLOSED, EXITED, CANCELLED, REJECTED, FAILED}
     * CE/PE inference:
     * - from human symbol suffix " CE"/" PE"
     * - or from Upstox key last segment (e.g., ...|CE or ...|PE)
     */
    public Optional<StrategyService.PortfolioSide> getOpenPortfolioSide() {
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
        } catch (Exception ignored) {
            return null;
        }
    }
}
