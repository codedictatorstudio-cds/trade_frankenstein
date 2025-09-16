package com.trade.frankenstein.trader.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class UpstoxTradeMode {

    @Value("${trade.mode}")
    private String mode;

    public String getTradeMode() {
        log.info("Trade Mode: {}", mode);
        return mode;
    }

    public String updateTradeMode(String newMode) {
        this.mode = newMode;
        log.info("Updated Trade Mode: {}", mode);
        return mode;
    }

    public boolean isSandBox() {
        return "SANDBOX".equalsIgnoreCase(this.mode);
    }
}
