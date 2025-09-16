package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("risk_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskSnapshot {

    @Id
    private String id;

    private Instant asOf;

    private double riskBudgetLeft;   // ₹ left
    private Integer lotsUsed;
    private Integer lotsCap;

    private double dailyLossPct;     // 0..100
    private double ordersPerMinPct;  // 0..100

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
}
