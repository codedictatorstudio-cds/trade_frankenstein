package com.trade.frankenstein.trader.service.trades;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.entity.OrderEntity;
import com.trade.frankenstein.trader.model.entity.TradeEntity;
import com.trade.frankenstein.trader.model.upstox.BrokerTrade;
import com.trade.frankenstein.trader.repo.OrderRepository;
import com.trade.frankenstein.trader.repo.TradeRepository;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import com.trade.frankenstein.trader.service.upstox.UpstoxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradesService {

    private final UpstoxClient broker;
    private final TradeRepository tradesRepo;
    private final StreamGateway stream;
    private final TradeRepository tradeRepo;
    private final OrderRepository orderRepo;

    // ---------------- Existing: simple list ----------------
    @Transactional(readOnly = true)
    public Result<List<TradeRowDto>> listRecent(int limit) {
        int lim = (limit <= 0 ? 20 : Math.min(limit, 200));
        List<TradeEntity> rows = tradesRepo.findAll(
                PageRequest.of(0, lim, Sort.by(
                        Sort.Order.desc("closedAt"), Sort.Order.desc("filledAt"),
                        Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).getContent();
        List<TradeRowDto> out = new ArrayList<>(rows.size());
        for (TradeEntity t : rows) out.add(toRow(t));
        return Result.ok(out);
    }

    // ---------------- UI-v2: pagination + filters ----------------
    @Transactional(readOnly = true)
    public Result<Paged<TradeRowDto>> list(int page, int size, @Nullable TradeFilter filter) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);

        var pageData = tradesRepo.findAll(PageRequest.of(p, s, Sort.by(
                Sort.Order.desc("closedAt"), Sort.Order.desc("filledAt"),
                Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));

        List<TradeRowDto> mapped = new ArrayList<>(pageData.getNumberOfElements());
        for (TradeEntity t : pageData.getContent()) {
            if (!accept(t, filter)) continue;
            mapped.add(toRow(t));
        }

        return Result.ok(new Paged<>(mapped, p, s, pageData.getTotalElements(), pageData.hasNext()));
    }

    private boolean accept(TradeEntity t, @Nullable TradeFilter f) {
        if (f == null) return true;
        if (f.status != null && f.status != nzStatus(t.getStatus())) return false;
        if (f.side != null && f.side != nzSide(t.getSide())) return false;
        if (f.instrumentContains != null) {
            String inst = safe(t.getInstrumentSymbol());
            if (!inst.toLowerCase(Locale.ROOT).contains(f.instrumentContains.toLowerCase(Locale.ROOT))) return false;
        }
        if (f.from != null && t.getCreatedAt() != null && t.getCreatedAt().isBefore(f.from)) return false;
        return f.to == null || t.getCreatedAt() == null || !t.getCreatedAt().isAfter(f.to);
    }

    // ---------------- Details ----------------
    @Transactional(readOnly = true)
    public Result<TradeDetailDto> explain(Long tradeId) {
        if (tradeId == null || tradeId <= 0) return Result.fail("BAD_REQUEST", "Invalid tradeId");
        return tradesRepo.findById(tradeId)
                .map(t -> Result.ok(toDetail(t)))
                .orElseGet(() -> Result.fail("NOT_FOUND", "Trade not found: " + tradeId));
    }

    // ---------------- Mapping ----------------
    private TradeRowDto toRow(TradeEntity t) {
        Instant time = coalesce(t.getClosedAt(), t.getFilledAt(), t.getCreatedAt(), Instant.now());
        return new TradeRowDto(
                t.getId(),
                t.getPublicId(),
                t.getBrokerTradeId(),
                time,
                safe(t.getInstrumentSymbol()),
                nzSide(t.getSide()),
                nzi(t.getQuantity()),
                nz(t.getEntryPrice()),
                nz(t.getExitPrice()),
                nzStatus(t.getStatus()),
                nz(t.getRealizedPnl())
        );
    }

    private TradeDetailDto toDetail(TradeEntity t) {
        TradeRowDto row = toRow(t);
        return new TradeDetailDto(
                row.id(), row.publicId(), row.brokerTradeId(), row.time(),
                row.instrument(), row.side(), row.quantity(), row.entryPrice(), row.exitPrice(),
                row.status(), row.realizedPnl(),
                coalesce(t.getFilledAt(), row.time()),
                coalesce(t.getClosedAt(), row.time()),
                coalesce(t.getCreatedAt(), row.time()),
                coalesce(t.getUpdatedAt(), row.time())
        );
    }

    // ---------------- Helpers ----------------
    @SafeVarargs
    private static <T> T coalesce(T... opts) {
        for (T v : opts) if (v != null) return v;
        return null;
    }

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    private static int nzi(Number n) {
        return n == null ? 0 : (int) Math.round(n.doubleValue());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static OrderSide nzSide(OrderSide s) {
        return s == null ? OrderSide.BUY : s;
    }

    private static TradeStatus nzStatus(TradeStatus s) {
        return s == null ? TradeStatus.PENDING : s;
    }

    // ---------------- DTOs (inner) ----------------
    public record TradeRowDto(Long id, String publicId, String brokerTradeId, Instant time, String instrument,
                              OrderSide side, int quantity, BigDecimal entryPrice, BigDecimal exitPrice,
                              TradeStatus status, BigDecimal realizedPnl) {
    }

    public record TradeDetailDto(Long id, String publicId, String brokerTradeId, Instant time, String instrument,
                                 OrderSide side, int quantity, BigDecimal entryPrice, BigDecimal exitPrice,
                                 TradeStatus status, BigDecimal realizedPnl, Instant filledAt, Instant closedAt,
                                 Instant createdAt, Instant updatedAt) {
    }

    public record Paged<T>(List<T> items, int page, int size, long totalElements, boolean hasNext) {
    }

    public record TradeFilter(@Nullable TradeStatus status, @Nullable OrderSide side,
                              @Nullable String instrumentContains, @Nullable Instant from, @Nullable Instant to) {
    }

    @Scheduled(fixedDelayString = "${trade.trades.reconcile-ms:45000}")
    @Transactional
    public void reconcileToday() {
        if (!inMarketHours()) return;

        // Keep your existing call — whatever it returns:
        var r = broker.listTradesToday();
        if (!r.isOk() || r.get() == null) return;

        for (BrokerTrade any : r.get()) {
            // Accept BrokerTrade or UpstoxTrade without reflection
            String tradeId = any.tradeId;

            if (tradeId.isBlank()) continue;
            if (tradeRepo.existsByBrokerTradeId(tradeId)) continue;

            TradeEntity t = mapFrom(any);

            tradeRepo.save(t);
            try {
                stream.send("trade.created", t);
            } catch (Throwable ignored) {
            }
        }
    }

    // Overloads — use your existing mapping for UpstoxTrade; add only this one:
    private TradeEntity mapFrom(BrokerTrade bt) {
        TradeEntity t = new TradeEntity();
        t.setPublicId(java.util.UUID.randomUUID().toString());
        t.setBrokerTradeId(bt.getTradeId());
        OrderEntity order = null;
        if (bt.getOrderId() != null) {
            order = orderRepo.findByPublicId(bt.getOrderId()).get();
        }
        t.setOrder(order);
        t.setInstrumentSymbol(
                firstNonBlank(bt.getTradingSymbol(), bt.getInstrumentToken(), "—")
        );
        t.setSide(bt.getSide());
        t.setQuantity(bt.getQuantity());
        t.setEntryPrice(bt.averagePrice);
        t.setExitPrice(null);
        t.setStatus(com.trade.frankenstein.trader.enums.TradeStatus.FILLED);
        t.setFilledAt(Instant.parse(bt.orderTimestamp));
        t.setClosedAt(null);
        t.setRealizedPnl(null);
        return t;
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) if (s != null && !s.trim().isEmpty()) return s.trim();
        return null;
    }

    private boolean inMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

}
