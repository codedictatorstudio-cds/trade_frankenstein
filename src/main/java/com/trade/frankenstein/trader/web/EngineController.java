// EngineController.java
package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.service.EngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/engine")
@RequiredArgsConstructor
@Slf4j
public class EngineController {

    private final EngineService engine;

    @PostMapping("/start")
    public ResponseEntity<?> start() {
        log.info("Engine start requested");
        return Http.from(engine.startEngine());
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        log.info("Engine stop requested");
        return Http.from(engine.stopEngine());
    }

    @GetMapping("/state")
    public ResponseEntity<?> state() {
        return Http.from(engine.getEngineState());
    }
}
