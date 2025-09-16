package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.model.upstox.PlaceOrderRequest;
import com.trade.frankenstein.trader.service.RiskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/risk")
public class RiskController {

    @Autowired
    private RiskService riskService;

    @GetMapping("/summary")
    public ResponseEntity<Result<RiskSnapshot>> getSummary() {
        return ResponseEntity.ok(riskService.getSummary());
    }

    @PostMapping("/check")
    public ResponseEntity<Result<Void>> check(@RequestBody PlaceOrderRequest req) {
        return ResponseEntity.ok(riskService.checkOrder(req));
    }
}

