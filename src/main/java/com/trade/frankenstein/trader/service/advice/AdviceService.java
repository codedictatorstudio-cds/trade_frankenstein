package com.trade.frankenstein.trader.service.advice;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderType;
import com.trade.frankenstein.trader.model.entity.AdviceEntity;
import com.trade.frankenstein.trader.model.upstox.PlaceOrderResponse;
import com.trade.frankenstein.trader.repo.AdviceRepository;
import com.trade.frankenstein.trader.service.orders.OrdersService;
import com.trade.frankenstein.trader.service.risk.RiskService;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdviceService {

    private final AdviceRepository adviceRepo;
    private final RiskService riskService;
    private final OrdersService ordersService;
    private final StreamGateway stream;

    // cooldown memory to avoid duplicate advices
    private final Map<String, Instant> lastAdviceAt = new ConcurrentHashMap<>();

    // ---------------- Card: basic list (kept) ----------------

    @Transactional(readOnly = true)
    public Result<List<AdviceRowDto>> list() {
        List<AdviceEntity> rows = adviceRepo
                .findAll(PageRequest.of(0, 100, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .getContent();
        List<AdviceRowDto> out = new ArrayList<>(rows.size());
        for (AdviceEntity a : rows) out.add(toRow(a));
        return Result.ok(out);
    }

    // ---------------- UI-v2: pagination + filters ----------------

    @Transactional(readOnly = true)
    public Result<Paged<AdviceRowDto>> list(int page, int size, @Nullable AdviceFilter filter) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);

        var pageData = adviceRepo.findAll(PageRequest.of(p, s,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));

        // lightweight in-memory filters (no new repo methods)
        List<AdviceRowDto> mapped = new ArrayList<>(pageData.getNumberOfElements());
        for (AdviceEntity a : pageData.getContent()) {
            if (!accept(a, filter)) continue;
            mapped.add(toRow(a));
        }

        return Result.ok(new Paged<>(
                mapped,
                p,
                s,
                pageData.getTotalElements(),    // note: total before filters
                pageData.hasNext()
        ));
    }

    private boolean accept(AdviceEntity a, @Nullable AdviceFilter f) {
        if (f == null) return true;
        if (f.status != null && !f.status.equalsIgnoreCase(safe(a.getStatus().name()))) return false;
        if (f.side != null && f.side != normalize(a.getSide())) return false;
        if (f.instrumentContains != null) {
            String inst = safe(a.getInstrumentSymbol());
            if (!inst.toLowerCase(Locale.ROOT).contains(f.instrumentContains.toLowerCase(Locale.ROOT))) return false;
        }
        if (f.from != null && a.getCreatedAt() != null && a.getCreatedAt().isBefore(f.from)) return false;
        if (f.to != null && a.getCreatedAt() != null && a.getCreatedAt().isAfter(f.to)) return false;
        return true;
    }

    // ---------------- Details ----------------

    @Transactional(readOnly = true)
    public Result<AdviceDetailDto> get(Long adviceId) {
        return adviceRepo.findById(adviceId)
                .map(a -> Result.ok(toDetail(a)))
                .orElseGet(() -> Result.fail("NOT_FOUND", "Advice not found: " + adviceId));
    }

    // ---------------- Execute / Dismiss ----------------

    @Transactional
    public Result<AdviceDetailDto> execute(Long adviceId) {
        AdviceEntity a = adviceRepo.findById(adviceId).orElse(null);
        if (a == null) return Result.fail("ADVICE_NOT_FOUND", "Advice not found");
        if (a.getStatus() == AdviceStatus.EXECUTED) return Result.fail("ALREADY_EXECUTED", "Already executed");
        if (a.getLots() <= 0) return Result.fail("BAD_REQUEST", "Lots must be > 0");


        OrderDraft draft = fromAdvice(a);

        Result<Void> risk = riskService.checkOrder(draft);
        if (!risk.isOk()) return Result.fail(risk.getError());

        Result<PlaceOrderResponse> placed = ordersService.placeOrder(draft);
        if (!placed.isOk()) return Result.fail(placed.getError());

        String orderId = placed.get().orderIds.isEmpty() ? null : placed.get().orderIds.get(0);

        a.setStatus(AdviceStatus.EXECUTED);
        if (orderId != null) a.setOrderPublicId(orderId);
        adviceRepo.save(a);

        riskService.noteOrderPlaced();

        AdviceDetailDto payload = toDetail(a).withOrderId(orderId);
        try {
            stream.send("advice.updated", payload);
            stream.send("trade.created", payload);
        } catch (Throwable ignored) {
            log.info("Stream send failed: advice.updated / trade.created");
        }

        return Result.ok(payload);
    }

    @Transactional
    public Result<Void> dismiss(Long adviceId) {
        AdviceEntity a = adviceRepo.findById(adviceId).orElse(null);
        if (a == null) return Result.fail("NOT_FOUND", "Advice not found: " + adviceId);

        String status = safe(a.getStatus().name());
        if (isTerminal(status))
            return Result.fail("INVALID_STATE", "Advice already " + status.toUpperCase(Locale.ROOT));

        a.setStatus(AdviceStatus.DISMISSED);
        adviceRepo.save(a);

        AdviceDetailDto payload = toDetail(a);
        try {
            stream.send("advice.updated", payload);
        } catch (Throwable ignored) {
        }
        return Result.ok();
    }

    // ---------------- Mapping ----------------

    private AdviceRowDto toRow(AdviceEntity a) {
        return new AdviceRowDto(
                a.getId(),
                a.getCreatedAt(),
                safe(a.getInstrument().getSymbol()),
                normalize(a.getSide()),
                clamp0to100(a.getConfidence()),
                clamp0to100(a.getTechScore()),
                clamp0to100(a.getNewsScore()),
                safe(a.getStatus().name())
        );
    }

    private AdviceDetailDto toDetail(AdviceEntity a) {
        AdviceRowDto row = toRow(a);
        return new AdviceDetailDto(
                row,
                safe(a.getReason()),
                safe(a.getOrderPublicId())
        );
    }

    private OrderDraft fromAdvice(AdviceEntity a) {

        return new OrderDraft(
                safe(a.getInstrumentSymbol()),
                0L,
                nz(a.getLots()),
                75,
                normalize(a.getSide()),
                a.getOrderType() == null ? OrderType.MARKET : a.getOrderType(),
                a.getPrice(),
                null,             // triggerPrice not in entity yet
                "DAY",            // defaults for UI-v2 control
                "I",
                null,             // disclosedQuantity
                false,            // isAmo
                true,             // slice
                null              // tag
        );
    }


    private static boolean isTerminal(String status) {
        if (status == null) return false;
        String s = status.trim().toUpperCase(Locale.ROOT);
        return "EXECUTED".equals(s) || "DISMISSED".equals(s);
    }

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    private static int clamp0to100(Integer n) {
        if (n == null) return 0;
        int i = n;
        return Math.max(0, Math.min(100, i));
    }

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }

    private static OrderSide normalize(OrderSide side) {
        return side == null ? OrderSide.BUY : side;
    }

    // ---------------- DTOs ----------------

    @Value
    public static class AdviceRowDto {
        Long id;
        Instant time;
        String instrument;
        OrderSide side;
        int confidence;
        int tech;
        int news;
        String status;
    }

    @Value
    public static class AdviceDetailDto {
        Long id;
        Instant time;
        String instrument;
        OrderSide side;
        int confidence;
        int tech;
        int news;
        String status;
        String reason;
        String orderId;

        public AdviceDetailDto(AdviceRowDto row, String reason, String orderId) {
            this.id = row.getId();
            this.time = row.getTime();
            this.instrument = row.getInstrument();
            this.side = row.getSide();
            this.confidence = row.getConfidence();
            this.tech = row.getTech();
            this.news = row.getNews();
            this.status = row.getStatus();
            this.reason = reason;
            this.orderId = orderId;
        }

        public AdviceDetailDto withOrderId(String newOrderId) {
            return new AdviceDetailDto(new AdviceRowDto(id, time, instrument, side, confidence, tech, news, status), reason, newOrderId);
        }
    }

    /**
     * Extended to carry SL/SL-LIMIT & UI knobs (still one inner class; no new files).
     *
     * @param triggerPrice      NEW: required for SL/SL_LIMIT
     * @param validity          e.g., DAY/IOC
     * @param product           I/D/MTF
     * @param disclosedQuantity optional
     * @param isAmo             after-market order
     * @param slice             let broker auto-slice by freeze qty
     * @param tag               optional client tag
     */
    @Builder
    public record OrderDraft(String symbol, long instrumentToken, int lots, int lotSize,
                             OrderSide side, OrderType orderType, BigDecimal price,
                             BigDecimal triggerPrice, String validity, String product, Integer disclosedQuantity,
                             boolean isAmo, boolean slice, String tag) {
        public int getQuantity() {
            return lots * Math.max(1, lotSize);
        }
    }

    public record Paged<T>(List<T> items, int page, int size, long totalElements, boolean hasNext) {
    }

    public record AdviceFilter(@Nullable String status, @Nullable OrderSide side, @Nullable String instrumentContains,
                               @Nullable Instant from, @Nullable Instant to) {
    }
}
