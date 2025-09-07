package com.trade.frankenstein.trader.service.orders;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.OrderType;
import com.trade.frankenstein.trader.model.upstox.*;
import com.trade.frankenstein.trader.service.advice.AdviceService;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import com.trade.frankenstein.trader.service.upstox.UpstoxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrdersService {

    private final StreamGateway stream;
    private final UpstoxClient broker;

    // ---------------- Place / Cancel / Get / List ----------------

    public Result<PlaceOrderResponse> placeOrder(AdviceService.OrderDraft draft) {
        try {
            Objects.requireNonNull(draft, "draft");
            // Resolve instrument token if not provided
            long token = draft.instrumentToken();
            if (token <= 0) {
                token = tryResolveInstrumentToken(draft.symbol());
                if (token <= 0)
                    return Result.fail("CONTRACT_NOT_RESOLVED", "Unable to resolve instrument token for: " + draft.symbol());
            }

            Result<Void> v = validateDraft(draft);
            if (!v.isOk()) return Result.fail(v.getError());

            PlaceOrderRequest req = PlaceOrderRequest.builder()
                    .instrumentToken(token)
                    .side(draft.side())
                    .orderType(draft.orderType())
                    .quantity(draft.getQuantity())
                    .price(priceFor(draft))
                    .triggerPrice(draft.triggerPrice())
                    .product(draft.product() == null ? "I" : draft.product())  // INTRADAY by default
                    .validity(draft.validity() == null ? "DAY" : draft.validity())
                    .isAmo(draft.isAmo())
                    .slice(draft.slice())
                    .tag(draft.tag())
                    .disclosedQuantity(draft.disclosedQuantity())
                    .build();

            Result<PlaceOrderResponse> placed = broker.placeOrder(req);
            if (!placed.isOk()) return Result.fail(placed.getError());
            try {
                stream.send("order.placed", placed.get());
            } catch (Throwable ignored) {
                log.info("stream send failed");
            }
            return placed;
        } catch (Exception e) {
            log.warn("placeOrder failed: {}", e.toString());
            return Result.fail(e);
        }
    }

    public Result<CancelOrderResponse> cancelOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) return Result.fail("BAD_REQUEST", "orderId is required");
        Result<CancelOrderResponse> r = broker.cancelOrder(orderId);
        if (r.isOk()) {
            try {
                stream.send("order.canceled", r.get());
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    public Result<BrokerOrder> getOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) return Result.fail("BAD_REQUEST", "orderId is required");
        return broker.getOrder(orderId);
    }

    public Result<List<BrokerOrder>> listOrders() {
        return broker.listOrders();
    }

    // ---------------- NEW: Modify order ----------------

    public Result<ModifyOrderResponse> modifyOrder(ModifyOrderRequest req) {
        if (req == null) return Result.fail("BAD_REQUEST", "request required");
        Result<ModifyOrderResponse> r = broker.modifyOrder(req);
        if (r.isOk()) {
            try {
                stream.send("order.updated", r.get());
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    // ---------------- Portfolio (no new service file) ----------------

    @Scheduled(fixedDelayString = "${trade.portfolio.refresh-ms:60000}")
    public void refreshPortfolioScheduled() {
        if (!isMarketHoursNowIst()) return;
        try {
            var a = broker.getPositions();
            var b = broker.getPortfolio();
            if (a.isOk() || b.isOk()) {
                stream.send("portfolio.snapshot", new PortfolioSnapshotDto(
                        a.isOk() ? a.get() : List.of(),
                        b.isOk() ? b.get() : List.of()
                ));
            }
        } catch (Throwable t) {
            log.info("refreshPortfolioScheduled failed: {}", t.getLocalizedMessage());
        }
    }

    private static boolean isMarketHoursNowIst() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    // ---------------- Validation helpers ----------------

    private Result<Void> validateDraft(AdviceService.OrderDraft d) {
        if (d.getQuantity() <= 0)
            return Result.fail("BAD_REQUEST", "quantity must be >= 1");
        OrderType t = d.orderType() == null ? OrderType.MARKET : d.orderType();

        switch (t) {
            case MARKET:
                if (d.price() != null || d.triggerPrice() != null)
                    return Result.fail("BAD_REQUEST", "MARKET must not set price/triggerPrice");
                return Result.ok();
            case LIMIT:
                if (nonPositive(d.price()))
                    return Result.fail("BAD_REQUEST", "LIMIT requires positive price");
                if (d.triggerPrice() != null)
                    return Result.fail("BAD_REQUEST", "LIMIT must not set triggerPrice");
                return Result.ok();
            case STOP_MARKET:
                if (d.triggerPrice() == null || nonPositive(d.triggerPrice()))
                    return Result.fail("BAD_REQUEST", "SL requires positive triggerPrice");
                if (d.price() != null)
                    return Result.fail("BAD_REQUEST", "SL must not set price (use SL_LIMIT for both)");
                return Result.ok();
            case STOP_LIMIT:
                if (nonPositive(d.price()) || nonPositive(d.triggerPrice()))
                    return Result.fail("BAD_REQUEST", "SL_LIMIT requires price and triggerPrice > 0");
                return Result.ok();
            default:
                return Result.fail("BAD_REQUEST", "Unsupported order type: " + t.name());
        }
    }

    private static BigDecimal priceFor(AdviceService.OrderDraft d) {
        OrderType t = d.orderType() == null ? OrderType.MARKET : d.orderType();
        return (t == OrderType.LIMIT || t == OrderType.STOP_LIMIT) ? d.price() : null;
    }

    private static boolean nonPositive(BigDecimal p) {
        return p == null || p.signum() <= 0;
    }


    /**
     * Temporary symbol→token resolver.
     * For now: accepts numeric symbol (already token) or returns 0.
     * Replace with OptionContractRepository-based resolver.
     */
    private long tryResolveInstrumentToken(String symbol) {
        if (symbol == null) return 0L;
        String s = symbol.trim();
        // if user passes numeric token as "123456"
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignore) {
            log.error("Invalid instrument token: {}", s);
        }
        return 0L;
    }


    // ---------------- Scheduled portfolio sync (market hours) ----------------
    @Scheduled(fixedDelayString = "${trade.portfolio.refresh-ms:60000}")
    public void syncPortfolio() {
        try {
            // Simple market-hours guard (Mon–Fri 09:15–15:30 IST)
            ZonedDateTime nowIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            DayOfWeek dow = nowIst.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
            LocalTime t = nowIst.toLocalTime();
            if (t.isBefore(LocalTime.of(9, 15)) || t.isAfter(LocalTime.of(15, 30))) return;

            var snap = broker.getPortfolioSnapshot();
            if (snap.isOk() && snap.get() != null) {
                PortfolioSnapshot p = snap.get();
                stream.send("portfolio.snapshot", new PortfolioSnapshotDto(p.positions, p.holdings));
            }
        } catch (Throwable ignored) {
        }
    }
// ---------------- DTOs (inner) ----------------

    public record PortfolioSnapshotDto(List<UpstoxPosition> positions, List<UpstoxHolding> holdings) {
    }
}
