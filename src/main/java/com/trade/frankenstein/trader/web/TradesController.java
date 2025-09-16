package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.service.TradesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
