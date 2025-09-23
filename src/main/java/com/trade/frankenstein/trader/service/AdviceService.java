package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.core.FastStateStore;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AdviceService {

    @Autowired
    private AdviceRepo adviceRepo;
    @Autowired
    private OrdersService ordersService;     // risk checks + Upstox call
    @Autowired
    private StreamGateway stream;
    @Autowired
    private FastStateStore fast;             // Step-3: fast guards/dedupe

    // ---------- READ ----------
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
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");
            Optional<Advice> opt = adviceRepo.findById(adviceId);
            return opt.map(Result::ok).orElseGet(() -> Result.fail("NOT_FOUND", "Advice not found"));
        } catch (Exception t) {
            log.error("advice.get({}) failed", adviceId, t);
            return Result.fail(t);
        }
    }

    // ---------- WRITE ----------

    /**
     * Persist a new Advice and emit advice.new (Step-3 dedupe on (instrumentToken, side) for 60s).
     */
    @Transactional
    public Result<Advice> create(Advice draft) {
        try {
            if (draft == null) return Result.fail("BAD_REQUEST", "Advice payload required");

            // defaults + validation
            if (draft.getStatus() == null) draft.setStatus(AdviceStatus.PENDING);
            if (draft.getProduct() == null) draft.setProduct("MIS");
            if (draft.getValidity() == null) draft.setValidity("DAY");
            assertReadyForCreationOrExecution(draft);

            // Step-3: dedupe window (60s) to avoid duplicate PENDING advices
            String side = String.valueOf(draft.getTransaction_type());
            String token = nullToEmpty(draft.getInstrument_token());
            String key = "adv:d:" + token + ":" + side;
            boolean first = fast.setIfAbsent(key, "1", Duration.ofSeconds(60));
            if (!first) {
                return Result.fail("DUPLICATE", "Similar advice exists (60s window)");
            }

            draft.setCreatedAt(Instant.now());
            draft.setUpdatedAt(Instant.now());
            Advice saved = adviceRepo.save(draft);

            stream.send("advice.new", saved);

            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.create failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Execute a PENDING advice: build typed PlaceOrderRequest, place it via OrdersService,
     * mark EXECUTED with broker orderId if available, emit advice.updated, and notify engine.
     */
    @Transactional
    public Result<Advice> execute(String adviceId) {
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");

            Advice a = adviceRepo.findById(adviceId).orElse(null);
            if (a == null) return Result.fail("NOT_FOUND", "Advice not found");
            if (a.getStatus() != AdviceStatus.PENDING) {
                log.info("advice.execute: {} not PENDING (status={}), skip", adviceId, a.getStatus());
                return Result.ok(a);
            }
            assertReadyForCreationOrExecution(a);

            PlaceOrderRequest req = buildUpstoxRequest(a);
            Result<PlaceOrderResponse> r = ordersService.placeOrder(req);
            if (r == null || !r.isOk() || r.get() == null || r.get().getData() == null) {
                String err = (r == null) ? "Order placement failed"
                        : (r.getError() == null ? "Order placement failed" : r.getError());
                return Result.fail((r == null || r.getErrorCode() == null) ? "ORDER_ERROR" : r.getErrorCode(), err);
            }

            String orderId = r.get().getData().getOrderId();
            a.setOrder_id(orderId);
            a.setStatus(AdviceStatus.EXECUTED);
            a.setUpdatedAt(Instant.now());

            Advice saved = adviceRepo.save(a);
            stream.send("advice.updated", saved);
            log.info("advice.execute: placed order {} for {}", orderId, a.getSymbol());
            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.execute failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Mark DISMISSED and emit advice.updated.
     */
    @Transactional
    public Result<Advice> dismiss(String adviceId) {
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");
            Advice a = adviceRepo.findById(adviceId).orElse(null);
            if (a == null) return Result.fail("NOT_FOUND", "Advice not found");
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

    // ---------- Helpers ----------
    private PlaceOrderRequest buildUpstoxRequest(Advice a) {
        // Uses Upstox SDK enums directly from Advice string fields (must match SDK enum names).
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Strict checks so OrdersService doesn’t receive half-filled payloads.
     */
    private void assertReadyForCreationOrExecution(Advice a) {
        if (a == null) throw new IllegalArgumentException("Advice is null");
        if (isBlank(a.getInstrument_token()))
            throw new IllegalStateException("instrumentToken is required on Advice");
        if (isBlank(a.getSymbol()))
            throw new IllegalStateException("tradingSymbol is required on Advice");
        if (isBlank(a.getOrder_type()))
            throw new IllegalStateException("orderType is required on Advice");
        if (a.getTransaction_type() == null)
            throw new IllegalStateException("transactionType (BUY/SELL) is required on Advice");
        if (a.getQuantity() <= 0)
            throw new IllegalStateException("quantity must be > 0 on Advice");
    }
}
