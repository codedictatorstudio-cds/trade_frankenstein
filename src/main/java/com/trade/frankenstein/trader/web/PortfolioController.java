// PortfolioController.java
package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolio;

    @GetMapping("/positions")
    public ResponseEntity<?> listPositions() {
        return Http.from(portfolio.getHoldings());
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        return Http.from(portfolio.getPortfolioSummary());
    }
}
