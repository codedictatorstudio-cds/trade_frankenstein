package com.trade.frankenstein.trader.model;

import com.trade.frankenstein.trader.enums.ThrottleStatus;
import com.trade.frankenstein.trader.enums.Trend;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class RegimeDecisionModel {


    // --- Backend-provided ---
    private int confidencePct;            // 0..100 (clamped)
    private Trend trend;                  // regime direction
    private Double riskReward;            // e.g., 2.1
    private Double slippagePct;           // e.g., 0.03 -> "0.03%"
    private ThrottleStatus throttleStatus;// OK/WARN/THROTTLED
    private List<String> reasons;         // ordered bullet points
    private Instant asOf;                 // snapshot timestamp
}
