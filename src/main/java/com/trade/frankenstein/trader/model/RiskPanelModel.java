package com.trade.frankenstein.trader.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class RiskPanelModel {

    // --- Backend-provided ---
    private BigDecimal riskBudgetLeftInr; // e.g., 8420 → "₹ 8,420"
    private int lotsUsed;                 // e.g., 4
    private int lotsCap;                  // e.g., 6
    private double dailyLossPct;          // 0..100
    private double ordersPerMinutePct;    // 0..100
    private Instant asOf;                 // snapshot time

    // Getters/setters …
}
