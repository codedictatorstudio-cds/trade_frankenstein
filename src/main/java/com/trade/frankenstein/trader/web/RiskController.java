package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.documents.RiskEvent;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.service.RiskService;
import com.upstox.api.PlaceOrderRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

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

    // Query recent risk events (for dashboard or monitoring UI)
    @GetMapping("/events/recent")
    public List<RiskEvent> recentEvents(@RequestParam(defaultValue = "50") int lastN) {
        return riskService.getRecentRiskEvents(lastN);
    }

    // Query events for a time period
    @GetMapping("/events/range")
    public List<RiskEvent> eventsBetween(@RequestParam long from, @RequestParam long to) {
        return riskService.getEventsBetween(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
    }

    // Query recent risk snapshots (for a chart or operator analysis)
    @GetMapping("/snapshots/recent")
    public List<RiskSnapshot> recentSnapshots(@RequestParam(defaultValue = "30") int lastN) {
        return riskService.getRecentRiskSnapshots(lastN);
    }

    // Query snapshots for a time period
    @GetMapping("/snapshots/range")
    public List<RiskSnapshot> snapshotsBetween(@RequestParam long from, @RequestParam long to) {
        return riskService.getRiskSnapshotsBetween(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
    }
}

