package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
import com.upstox.api.PlaceOrderRequest;
import com.upstox.api.PlaceOrderResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AdviceService {

    @Autowired
    private AdviceRepo adviceRepo;
    @Autowired
    private OrdersService ordersService;   // risk checks + Upstox call
    @Autowired
    private StreamGateway stream;

    @Transactional(readOnly = true)
    public Result<List<Advice>> list() {
        try {
            List<Advice> advs = adviceRepo
                    .findAll(PageRequest.of(0, 100, Sort.by("createdAt").descending()))
                    .getContent();
            return Result.ok(advs);
        } catch (Exception t) {
            log.error("advice.list failed", t);
            return Result.fail(t);
        }
    }

    @Transactional(readOnly = true)
    public Result<Advice> get(String adviceId) {
        try {
            if (adviceId == null || adviceId.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "adviceId is required");
            }
            Optional<Advice> opt = adviceRepo.findById(adviceId);
            return opt.map(Result::ok).orElseGet(() -> Result.fail("NOT_FOUND", "Advice not found"));
        } catch (Exception t) {
            log.error("advice.get({}) failed", adviceId, t);
            return Result.fail(t);
        }
    }

    /**
     * Persist a new Advice and emit advice.new
     */
    @Transactional
    public Result<Advice> create(Advice draft) {
        try {
            if (draft == null) return Result.fail("BAD_REQUEST", "Advice payload required");
            if (isBlank(draft.getSymbol())) return Result.fail("BAD_REQUEST", "Symbol is required");
            if (draft.getTransaction_type() == null)
                return Result.fail("BAD_REQUEST", "Transaction type (BUY/SELL) is required");
            if (draft.getQuantity() <= 0) return Result.fail("BAD_REQUEST", "Quantity must be > 0");

            if (draft.getStatus() == null) draft.setStatus(AdviceStatus.PENDING);
            // direct timestamp setters (no reflection)
            draft.setCreatedAt(Instant.now());
            draft.setUpdatedAt(Instant.now());
            // AdviceService.create(...) — before saving:
            assertReadyForCreationOrExecution(draft);

            Advice saved = adviceRepo.save(draft);

            // stream entity directly (no mapper)
            stream.send("advice.new", saved);

            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.create failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Execute a PENDING advice: build typed PlaceOrderRequest, place it,
     * update Advice → EXECUTED with orderId (if available), then emit advice.updated.
     */
    @Transactional
    public Result<Advice> execute(String adviceId) {
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");

            Optional<Advice> opt = adviceRepo.findById(adviceId);
            if (opt.isEmpty()) return Result.fail("NOT_FOUND", "Advice not found");

            Advice a = opt.get();
            if (a.getStatus() != AdviceStatus.PENDING) {
                log.info("advice.execute: {} not PENDING (status={}), skip", adviceId, a.getStatus());
                return Result.ok(a);
            }

            PlaceOrderRequest req = buildUpstoxRequest(a);
            Result<PlaceOrderResponse> r = ordersService.placeOrder(req);
            if (r == null || !r.isOk() || r.get() == null) {
                String err = (r == null) ? "Order placement failed"
                        : (r.getError() == null ? "Order placement failed" : r.getError());
                return Result.fail((r == null || r.getErrorCode() == null) ? "ORDER_ERROR" : r.getErrorCode(), err);
            }

            PlaceOrderResponse placed = r.get();
            String orderId = placed.getData().getOrderId();

            // direct setters (no reflection)
            a.setOrder_id(orderId);
            a.setStatus(AdviceStatus.EXECUTED);
            a.setUpdatedAt(Instant.now());

            Advice saved = adviceRepo.save(a);
            // AdviceService.execute(...) — after fetching the entity and before placing the order:
            assertReadyForCreationOrExecution(a);

            // stream entity directly (no mapper)
            stream.send("advice.updated", saved);

            log.info("advice.execute: placed order {} for {}", orderId, a.getSymbol());
            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.execute failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Mark advice as DISMISSED and emit advice.updated.
     */
    @Transactional
    public Result<Advice> dismiss(String adviceId) {
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");
            Optional<Advice> opt = adviceRepo.findById(adviceId);
            if (opt.isEmpty()) return Result.fail("NOT_FOUND", "Advice not found");

            Advice a = opt.get();
            if (a.getStatus() == AdviceStatus.DISMISSED) return Result.ok(a);

            a.setStatus(AdviceStatus.DISMISSED);
            a.setUpdatedAt(Instant.now());
            Advice saved = adviceRepo.save(a);

            stream.send("advice.updated", saved);
            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.dismiss failed", t);
            return Result.fail(t);
        }
    }

    // =========================== Helpers ===========================

    private PlaceOrderRequest buildUpstoxRequest(Advice a) {
        // Strategy must populate these fields on Advice before calling create()

        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setInstrumentToken(a.getInstrument_token());
        req.setOrderType(PlaceOrderRequest.OrderTypeEnum.valueOf(a.getOrder_type()));
        req.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.valueOf(a.getTransaction_type()));
        req.setQuantity(a.getQuantity());
        req.setProduct(PlaceOrderRequest.ProductEnum.valueOf(a.getProduct()));
        req.setValidity(PlaceOrderRequest.ValidityEnum.valueOf(a.getValidity()));
        req.setPrice(a.getPrice());
        req.setTriggerPrice(a.getTrigger_price());
        req.setDisclosedQuantity(a.getDisclosed_quantity());
        req.setIsAmo(a.is_amo());
        req.setTag(a.getTag());
        return req;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // AdviceService.java — add this helper
    private void assertReadyForCreationOrExecution(Advice a) {
        if (a == null) throw new IllegalArgumentException("Advice is null");
        if (a.getInstrument_token() == null || a.getInstrument_token().trim().isEmpty())
            throw new IllegalStateException("instrumentToken is required on Advice");
        if (a.getSymbol() == null || a.getSymbol().trim().isEmpty())
            throw new IllegalStateException("tradingSymbol is required on Advice");
        if (a.getOrder_type() == null || a.getOrder_type().trim().isEmpty())
            throw new IllegalStateException("orderType is required on Advice");
        if (a.getTransaction_type() == null)
            throw new IllegalStateException("transactionType (BUY/SELL) is required on Advice");
        if (a.getQuantity() <= 0)
            throw new IllegalStateException("quantity must be > 0 on Advice");
    }

}
