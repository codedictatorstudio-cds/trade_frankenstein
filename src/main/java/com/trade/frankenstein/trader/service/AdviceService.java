package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.OrderStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.Order;
import com.trade.frankenstein.trader.model.upstox.PlaceOrderRequest;
import com.trade.frankenstein.trader.model.upstox.PlaceOrderResponse;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
import com.trade.frankenstein.trader.repo.documents.OrderRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class AdviceService {

    @Autowired private AdviceRepo adviceRepo;
    @Autowired private OrderRepo orderRepo;
    @Autowired private OrdersService ordersService;                 // single gate calls RiskService + UpstoxService
    @Autowired private StreamGateway stream;

    // ---------------------------------------------------------------------
    // Create (used by Engine/Strategy to publish fresh advices)
    // ---------------------------------------------------------------------
    @Transactional
    public Result<Advice> create(Advice draft) {
        if (draft == null) return Result.fail("BAD_REQUEST", "Advice payload required");
        if (draft.getSymbol() == null || draft.getSymbol().trim().isEmpty())
            return Result.fail("BAD_REQUEST", "Symbol is required");
        if (draft.getTransaction_type() == null)
            return Result.fail("BAD_REQUEST", "Transaction type (BUY/SELL) is required");
        if (draft.getQuantity() <= 0)
            return Result.fail("BAD_REQUEST", "Quantity must be > 0");

        if (draft.getStatus() == null) draft.setStatus(AdviceStatus.PENDING);
        Instant now = Instant.now();
        if (draft.getCreatedAt() == null) draft.setCreatedAt(now);
        draft.setUpdatedAt(now);

        Advice saved = adviceRepo.save(draft);

        // 🔔 Stream fresh advice to UI
        try {
            stream.send("advice.new", saved);
            log.info("Streamed advice.new id={}", saved.getId());
        } catch (Throwable t) {
            log.warn("Failed to stream advice.new: {}", t.toString());
        }
        return Result.ok(saved);
    }

    // ---------------------------------------------------------------------
    // List / Get
    // ---------------------------------------------------------------------
    @Transactional(readOnly = true)
    public Result<List<Advice>> list() {
        List<Advice> rows = adviceRepo
                .findAll(PageRequest.of(0, 200, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .getContent();
        return Result.ok(rows);
    }

    @Transactional(readOnly = true)
    public Result<List<Advice>> list(@Nullable String symbolContains,
                                     @Nullable AdviceStatus status,
                                     int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);

        List<Advice> out = new ArrayList<>();
        for (Advice a : adviceRepo.findAll(PageRequest.of(p, s, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))) {
            if (symbolContains != null) {
                String sym = a.getSymbol() == null ? "" : a.getSymbol();
                if (!sym.toLowerCase(Locale.ROOT).contains(symbolContains.toLowerCase(Locale.ROOT))) continue;
            }
            if (status != null && a.getStatus() != status) continue;
            out.add(a);
        }
        return Result.ok(out);
    }

    @Transactional(readOnly = true)
    public Result<Advice> get(String adviceId) {
        Advice a = adviceRepo.findById(adviceId).orElse(null);
        if (a == null) return Result.fail("NOT_FOUND", "Advice not found: " + adviceId);
        return Result.ok(a);
    }

    // ---------------------------------------------------------------------
    // Execute
    // ---------------------------------------------------------------------
    @Transactional
    public Result<Advice> execute(String adviceId) {
        Advice a = adviceRepo.findById(adviceId).orElse(null);
        if (a == null) return Result.fail("NOT_FOUND", "Advice not found");
        if (a.getStatus() == AdviceStatus.EXECUTED) return Result.fail("ALREADY_EXECUTED", "Already executed");
        if (a.getQuantity() <= 0) return Result.fail("BAD_REQUEST", "Quantity must be > 0");

        PlaceOrderRequest req = toUpstoxRequest(a);

        // Delegate to OrdersService (does risk checks + market-hours + broker call + its own SSE)
        Result<PlaceOrderResponse> placedRes = ordersService.placeOrder(req);
        if (placedRes == null || !placedRes.isOk() || placedRes.get() == null) {
            return Result.fail(placedRes == null ? "BROKER_ERROR" : placedRes.getErrorCode(),
                    placedRes == null ? "Failed to place order" : placedRes.getError());
        }
        PlaceOrderResponse placed = placedRes.get();

        // Extract order id (first id)
        String orderId = null;
        try {
            if (placed.getData() != null &&
                    placed.getData().getOrder_ids() != null &&
                    !placed.getData().getOrder_ids().isEmpty()) {
                orderId = placed.getData().getOrder_ids().get(0);
            }
        } catch (Throwable ignored) {}

        if (orderId == null) return Result.fail("BROKER_ERROR", "Order ID missing from broker response");

        // Update Advice
        a.setOrder_id(orderId);
        a.setStatus(AdviceStatus.EXECUTED);
        a.setUpdatedAt(Instant.now());
        adviceRepo.save(a);

        // Mirror Order doc (lightweight)
        try {
            Order o = Order.builder()
                    .id(orderId)
                    .symbol(a.getSymbol())
                    .order_type(a.getOrder_type())
                    .transaction_type(a.getTransaction_type())
                    .quantity(a.getQuantity())
                    .product(a.getProduct())
                    .validity(a.getValidity())
                    .price(a.getPrice())
                    .trigger_price(a.getTrigger_price())
                    .disclosed_quantity(a.getDisclosed_quantity())
                    .is_amo(a.is_amo())
                    .slice(a.isSlice())
                    .tag(a.getTag())
                    .status(OrderStatus.NEW)
                    .filled_quantity(0)
                    .pending_quantity(a.getQuantity())
                    .average_price(0.0)
                    .placed_at(Instant.now())
                    .updated_at(Instant.now())
                    .build();
            orderRepo.save(o);
        } catch (Throwable t) {
            log.warn("Order save error: {}", t.toString());
        }

        // 🔔 Notify UI that this advice changed
        try {
            stream.send("advice.updated", a);
            log.info(" Streamed advice.updated id={}", a.getId());
        } catch (Throwable t) {
            log.warn(" Failed to stream advice.updated: {}", t.toString());
        }

        return Result.ok(a);
    }

    // ---------------------------------------------------------------------
    // Dismiss
    // ---------------------------------------------------------------------
    @Transactional
    public Result<Void> dismiss(String adviceId) {
        Advice a = adviceRepo.findById(adviceId).orElse(null);
        if (a == null) return Result.fail("NOT_FOUND", "Advice not found: " + adviceId);
        if (a.getStatus() == AdviceStatus.EXECUTED || a.getStatus() == AdviceStatus.DISMISSED) {
            return Result.fail("INVALID_STATE", "Advice already " + (a.getStatus() == null ? "" : a.getStatus().name()));
        }
        a.setStatus(AdviceStatus.DISMISSED);
        a.setUpdatedAt(Instant.now());
        adviceRepo.save(a);

        // 🔔 Notify UI that this advice changed
        try {
            stream.send("advice.updated", a);
            log.info("Streamed advice.updated id={}", a.getId());
        } catch (Throwable t) {
            log.warn("Failed to stream advice.updated: {}", t.toString());
        }
        return Result.ok();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private static PlaceOrderRequest toUpstoxRequest(Advice a) {
        BigDecimal price = a.getPrice() > 0 ? BigDecimal.valueOf(a.getPrice()) : null;
        BigDecimal trigger = a.getTrigger_price() > 0 ? BigDecimal.valueOf(a.getTrigger_price()) : null;

        return PlaceOrderRequest.builder()
                .instrument_token(a.getInstrument_token())
                .order_type(a.getOrder_type())
                .transaction_type(a.getTransaction_type())
                .quantity(a.getQuantity())
                .product(a.getProduct())
                .validity(a.getValidity())
                .price(price)
                .trigger_price(trigger)
                .disclosed_quantity(a.getDisclosed_quantity())
                .is_amo(a.is_amo())
                .slice(a.isSlice())
                .tag(a.getTag())
                .build();
    }
}
