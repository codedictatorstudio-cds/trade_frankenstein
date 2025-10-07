package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.service.decision.DecisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/decision")
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionService decisionService;

    @GetMapping("/quality")
    public ResponseEntity<?> getQuality() {
        return Http.from(decisionService.getQuality());
    }
}
