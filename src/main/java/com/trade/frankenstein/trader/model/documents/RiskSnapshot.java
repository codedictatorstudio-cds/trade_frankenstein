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

    // --- Identity & time ---
    @Id
    private String id;

    /**
     * Wall-clock moment this snapshot represents (engine tick time).
     */
    private Instant asOf;

    // --- Master headroom switch (aggregated from guards) ---
    /**
     * True when it's safe to generate/enter new positions for this tick.
     */
    private Boolean riskHeadroomOk;

    // --- Circuit/Kill switches (feature-flag driven) ---
    /**
     * When true, never open new positions (global kill).
     */
    private Boolean killSwitchOpenNew;

    /**
     * When true, engine must not open new positions due to circuit breaker lockout.
     */
    private Boolean circuitBreakerLockout;

    /**
     * When true, daily loss/circuit has been tripped for the session.
     */
    private Boolean dailyCircuitTripped;

    // --- Daily P&L and budget ---
    /**
     * Today's realized P&L (₹).
     */
    private Double realizedPnlToday;

    /**
     * Today's unrealized/MTM P&L (₹), optional; may be null if not computed.
     */
    private Double unrealizedPnlToday;

    /**
     * Absolute loss amount considered for guards (₹).
     */
    private Double dailyLossAbs;

    /**
     * Daily loss percent used for DAILY_LOSS_GUARD (0..100).
     */
    private Double dailyLossPct;

    /**
     * Remaining risk budget in ₹ (post checks/sizing).
     */
    private Double riskBudgetLeft;

    /**
     * Lots currently in use vs cap for position sizing checks.
     */
    private Integer lotsUsed;
    private Integer lotsCap;

    // --- Order-rate & SL cooldown telemetry ---
    /**
     * Orders sent in the current 60s window.
     */
    private Integer ordersPerMin;

    /**
     * Orders-per-minute load as percent of cap (0..100).
     */
    private Double ordersPerMinPct;

    /**
     * Minutes since the last stop-loss fill, used by cooldown/2-SL rules.
     */
    private Integer minutesSinceLastSl;

    /**
     * Number of restrikes (re-entries after SL) taken today.
     */
    private Integer restrikesToday;

    // --- Hedging hooks (flags may read these to trigger actions) ---
    /**
     * True when AUTO_HEDGE_ON_VOL_SPIKE conditions are met for this tick.
     */
    private Boolean autoHedgeOnVolSpike;

    /**
     * True when DELTA_TARGET_HEDGE should be enforced by Engine/Decision.
     */
    private Boolean deltaTargetHedge;

    // --- Audit ---
    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
}

