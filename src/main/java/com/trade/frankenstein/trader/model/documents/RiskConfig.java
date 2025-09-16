package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Central risk guardrails used by RiskService
 */
@Document("risk_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskConfig {

    private @Id String id;

    private double maxDailyLoss;       // ₹
    private Integer lotsCap;
    private Integer ordersPerMinCap;
    private double perOrderRiskPct;    // 0..100
    private boolean enabled;

    private Instant asOf;
}
