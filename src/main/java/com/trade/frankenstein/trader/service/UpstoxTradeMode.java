package com.trade.frankenstein.trader.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class UpstoxTradeMode {

    private static UpstoxTradeMode instance;

    @Value("${trade.mode}")
    private String mode;

    // Private constructor to prevent instantiation
    private UpstoxTradeMode() {
        // Default constructor - Spring will populate the mode value
    }

    // Static method to get the instance
    public static synchronized UpstoxTradeMode getInstance() {
        if (instance == null) {
            instance = new UpstoxTradeMode();
        }
        return instance;
    }

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
