package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService market;

    /**
     * Latest traded price for a symbol/instrument key.
     */
    @GetMapping("/ltp")
    public ResponseEntity<?> getLtp(@RequestParam("symbol") String symbol) {
        return Http.from(market.getLtp(symbol));
    }

    /**
     * Current market regime snapshot.
     */
    @GetMapping("/regime")
    public ResponseEntity<?> getRegime() {
        return Http.from(market.getRegimeNow());
    }

    /**
     * Momentum Z-score as of an instant (defaults to now).
     */
    @GetMapping("/momentum")
    public ResponseEntity<?> getMomentum(
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return Http.from(market.getMomentumNow(asOf != null ? asOf : Instant.now()));
    }
}
