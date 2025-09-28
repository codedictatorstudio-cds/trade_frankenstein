package com.trade.frankenstein.trader.service;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.FlagName;
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
    private FastStateStore fast;             // Step-3: fast guards/dedupe
    @Autowired
    private FlagsService flags;              // Step-9: entry gates
    @Autowired
    private EventPublisher eventPublisher; // Step-10: Kafka publish

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---------- READ ----------
    @Transactional(readOnly = true)
    public Result<List<Advice>> list() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return Result.fail("user-not-logged-in");
        }
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in :: get");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");
            Optional<Advice> opt = adviceRepo.findById(adviceId);
            return opt.map(Result::ok).orElseGet(() -> Result.fail("NOT_FOUND", "Advice not found"));
        } catch (Exception t) {
            log.error("advice.get({}) failed", adviceId, t);
            return Result.fail(t);
        }
    }

    /**
     * Persist a new Advice and emit advice.new (Step-3 dedupe on (instrumentToken, side) for 60s).
     */
    @Transactional
    public Result<Advice> create(Advice draft) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in :: create");
            return Result.fail("user-not-logged-in");
        }
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

            publishAdviceEvent("advice.new", saved);
            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.create failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Execute a PENDING advice: build typed PlaceOrderRequest, place it via OrdersService,
     * mark EXECUTED with broker orderId if available, emit advice.updated, and notify engine.
     * <p>
     * Step-9: For BUY (new entry), block when any entry gate is ON in FlagsService.
     * SELL (exit) is allowed regardless so we can flatten positions.
     */
    @Transactional
    public Result<Advice> execute(String adviceId) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in :: execute");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");

            Advice a = adviceRepo.findById(adviceId).orElse(null);
            if (a == null) return Result.fail("NOT_FOUND", "Advice not found");
            if (a.getStatus() != AdviceStatus.PENDING) {
                log.info("advice.execute: {} not PENDING (status={}), skip", adviceId, a.getStatus());
                return Result.ok(a);
            }
            assertReadyForCreationOrExecution(a);

            // Hard entry gates only for BUY (allow SELL exits)
            String side = String.valueOf(a.getTransaction_type());
            if ("BUY".equalsIgnoreCase(side)) {
                if (flags.isOn(FlagName.KILL_SWITCH_OPEN_NEW)
                        || flags.isOn(FlagName.CIRCUIT_BREAKER_LOCKOUT)
                        || flags.isOn(FlagName.OPENING_5M_BLACKOUT)
                        || flags.isOn(FlagName.NOON_PAUSE_WINDOW)
                        || flags.isOn(FlagName.LATE_ENTRY_CUTOFF)) {
                    return Result.fail("ENTRY_BLOCKED", "Entry blocked by risk/time window flags");
                }
            }

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
            publishAdviceEvent("advice.executed", saved); // Step-10
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");
            Advice a = adviceRepo.findById(adviceId).orElse(null);
            if (a == null) return Result.fail("NOT_FOUND", "Advice not found");
            if (a.getStatus() == AdviceStatus.DISMISSED) return Result.ok(a);

            a.setStatus(AdviceStatus.DISMISSED);
            a.setUpdatedAt(Instant.now());
            Advice saved = adviceRepo.save(a);

            publishAdviceEvent("advice.updated", saved); // Step-10
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

    // NEW: Step-10 helper placed near the bottom of the class
// ------------------------------------------------------

    /**
     * Step-10: Publish to Kafka "advice" topic (only when AdviceService is the source).
     * Emits compact JSON using JsonObject (no StringBuilder).
     */
    private void publishAdviceEvent(String event, Advice a) {
        try {
            if (a == null) return;

            JsonObject o = new JsonObject();
            o.addProperty("ts", java.time.Instant.now().toEpochMilli());
            o.addProperty("event", event);
            o.addProperty("source", "advice");

            if (a.getId() != null) o.addProperty("id", a.getId());
            if (a.getSymbol() != null) o.addProperty("symbol", a.getSymbol());
            if (a.getInstrument_token() != null) o.addProperty("instrument_token", a.getInstrument_token());
            if (a.getOrder_type() != null) o.addProperty("order_type", a.getOrder_type());
            if (a.getTransaction_type() != null)
                o.addProperty("transaction_type", a.getTransaction_type());
            o.addProperty("quantity", a.getQuantity());
            if (a.getProduct() != null) o.addProperty("product", a.getProduct());
            if (a.getValidity() != null) o.addProperty("validity", a.getValidity());
            if (a.getPrice() != null) o.addProperty("price", a.getPrice());
            if (a.getTrigger_price() != null) o.addProperty("trigger_price", a.getTrigger_price());
            if (a.getStatus() != null) o.addProperty("status", a.getStatus().name());

            // Key preference: symbol -> instrument_token -> id
            String key = a.getSymbol();
            if (key == null || key.trim().isEmpty()) key = a.getInstrument_token();
            if ((key == null || key.trim().isEmpty()) && a.getId() != null) key = a.getId();

            if (eventPublisher != null) {
                eventPublisher.publish(EventBusConfig.TOPIC_ADVICE, key, o.toString());
            }
        } catch (Throwable t) {
            // best-effort, never break advice flow
        }
    }
}