package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.service.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);

    @Autowired
    private StrategyService strategyService;

    @GetMapping("/generate")
    public Map<String, Object> generate() {
        int created = 0;
        try {
            created = strategyService.generateAdvicesNow();
            log.info("StrategyController.generate -> created {} advice(s)", created);
        } catch (Exception e) {
            log.warn("StrategyController.generate failed: {}", e.getMessage(), e);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("created", created);
        return resp;
    }
}
