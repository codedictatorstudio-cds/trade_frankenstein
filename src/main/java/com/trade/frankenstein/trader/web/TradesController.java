package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.service.trade.TradesService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradesController {

    private final TradesService trades;

    /**
     * Recent fills (default 20, max 200).
     */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        return Http.from(trades.listRecent(lim));
    }

    /**
     * Fetch a single trade document (includes the explain field if present).
     */
    @GetMapping("/{tradeId}")
    public ResponseEntity<?> get(@PathVariable("tradeId") String tradeId) {
        return Http.from(trades.get(tradeId));
    }

    /**
     * \"Why?\" endpoint kept for UI compatibility.
     * It derives the explanation from the trade’s `explain` field.
     */
    @GetMapping("/{tradeId}/explain")
    public ResponseEntity<?> explain(@PathVariable("tradeId") String tradeId) {
        Result<Trade> r = trades.get(tradeId);
        if (!r.isOk() || r.get() == null) {
            // Pass through the same error shape
            return Http.from(r);
        }
        String message = r.get().getExplain();
        Map<String, Object> payload = Collections.unmodifiableMap(
                new java.util.LinkedHashMap<String, Object>() {{
                    put("tradeId", tradeId);
                    put("explain", message);
                }}
        );
        return Http.from(Result.ok(payload));
    }

    @PostMapping("/place")
    public ResponseEntity<?> place(@RequestBody Trade trade,
                                   @RequestHeader("Idempotency-Key") String key) {
        Result<Trade> result = trades.placeTrade(trade, key);
        return result.isOk()
                ? ResponseEntity.ok(result.getData())
                : ResponseEntity.status(HttpStatus.CONFLICT).body(result.getError());
    }

    @PostMapping("/reconcile")
    public ResponseEntity<?> reconcile(@RequestParam String tradeId) {
        Result<Trade> result = trades.reconcileTrade(tradeId);
        return result.isOk()
                ? ResponseEntity.ok(result.getData())
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result.getError());
    }

    @GetMapping
    public Mono<Page<Trade>> list(Pageable pageable) {
        return trades.getTradesReactive(pageable);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) TradeStatus status,
                                    @RequestParam(required = false) OrderSide side,
                                    @RequestParam(required = false) String symbol,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                trades.listTradesEnhanced(page, size, status, side, symbol).getData()
        );
    }
}
