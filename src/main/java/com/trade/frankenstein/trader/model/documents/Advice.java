package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.ExecutionContext;
import com.trade.frankenstein.trader.enums.RiskCategory;
import com.trade.frankenstein.trader.enums.StrategyName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Enhanced Advice model with comprehensive auto-trading capabilities.
 * Includes UI fields + order draft fields aligned with PlaceOrderRequest.
 */
@Document("advices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Advice {
    @Id
    private String id;

    // Basic metadata
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;

    // Display and confidence metrics
    private String symbol; // display
    private Integer confidence; // 0..100
    private Integer tech; // 0..100
    private Integer news; // 0..100
    private String reason; // detailed reasoning

    // Status and execution tracking
    @Indexed
    private AdviceStatus status; // PENDING/EXECUTED/DISMISSED/etc.
    private String order_id; // broker order ID when executed
    private StrategyName strategy; // originating strategy

    // Trading instrument details
    @Indexed
    private String instrument_token;
    private String order_type; // MARKET/LIMIT/SL
    private String transaction_type; // BUY/SELL
    private int quantity;
    private String product; // MIS/NRML/CNC
    private String validity; // DAY/IOC
    private Float price;
    private String tag;
    private int disclosed_quantity;
    private Float trigger_price;
    private boolean is_amo;

    // NEW ENHANCED FIELDS FOR AUTO TRADING

    // Priority and lifecycle management
    @Builder.Default
    private Integer priorityScore = 50; // 0-100, higher = more urgent
    private String parentAdviceId; // for related advice tracking
    private ExecutionContext executionContext; // MANUAL/AUTO/RISK_TRIGGERED/STRATEGY
    private Instant expiresAt; // auto-expiry timestamp

    // Risk and P&L tracking
    private BigDecimal expectedPnl; // expected profit/loss
    @Builder.Default
    private RiskCategory riskCategory = RiskCategory.MEDIUM; // LOW/MEDIUM/HIGH
    @Builder.Default
    private Integer retryCount = 0; // execution retry attempts
    private String lastError; // last execution error message

    // Market context at creation
    private String marketRegime; // BULLISH/BEARISH/NEUTRAL at advice creation
    private BigDecimal marketSentimentScore; // sentiment score when created
    private Float volatilityScore; // ATR% or VIX proxy when created

    // Position and portfolio context
    private String positionType; // ENTRY/EXIT/HEDGE/REBALANCE
    private String portfolioAllocation; // percentage of portfolio this represents
    private Integer lotMultiplier; // for position sizing calculations

    // Execution quality metrics
    private BigDecimal executionPrice; // actual fill price
    private BigDecimal slippage; // difference from expected price
    private Long executionLatencyMs; // time from advice to execution
    private String executionQuality; // EXCELLENT/GOOD/FAIR/POOR

    // Risk management integration
    private BigDecimal stopLoss; // calculated stop loss level
    private BigDecimal takeProfit; // calculated take profit level
    private Integer maxHoldingMinutes; // maximum holding period
    private Boolean emergencyExit; // flag for emergency exit scenarios

    // Strategy-specific metadata
    private String strategySignal; // detailed strategy signal information
    private BigDecimal signalStrength; // 0-100, strength of the signal
    private String entryConditions; // conditions that triggered entry
    private String exitConditions; // conditions for exit

    // Performance tracking
    private BigDecimal realizedPnl; // actual P&L after exit
    private Integer holdingPeriodMinutes; // actual holding period
    private Boolean wasSuccessful; // whether the trade met objectives
    private String performanceNotes; // post-trade analysis notes

    // Audit and compliance
    private String createdBy; // system/user identifier
    private String approvedBy; // if manual approval required
    private String riskApproval; // risk system approval reference
    private Boolean requiresManualReview; // flag for manual review

    // Helper methods for business logic
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isHighPriority() {
        return priorityScore != null && priorityScore >= 80;
    }

    public boolean isEntry() {
        return "BUY".equals(transaction_type);
    }

    public boolean isExit() {
        return "SELL".equals(transaction_type);
    }

    public boolean canRetry() {
        return retryCount != null && retryCount < 3;
    }

    public void incrementRetry() {
        this.retryCount = (this.retryCount != null ? this.retryCount : 0) + 1;
    }
}
