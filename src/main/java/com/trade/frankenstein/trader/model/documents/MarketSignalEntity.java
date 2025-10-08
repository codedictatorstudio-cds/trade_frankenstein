package com.trade.frankenstein.trader.model.documents;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "market_signals")
@CompoundIndex(name = "instrument_signal_type_idx",
        def = "{'instrumentKey': 1, 'signalType': 1, 'timestamp': -1}")
@CompoundIndex(name = "active_signals_idx",
        def = "{'isActive': 1, 'confidence': -1}")
public class MarketSignalEntity {

    @Id
    private String id;

    @Field("instrument_key")
    @Indexed
    private String instrumentKey;

    @Field("signal_type")
    @Indexed
    private String signalType;

    @Field("signal_value")
    private BigDecimal signalValue;

    @Field("confidence")
    @Indexed
    private BigDecimal confidence;

    @Field("timestamp")
    @Indexed
    private LocalDateTime timestamp;

    @Field("timeframe")
    @Indexed
    private String timeframe;

    @Field("direction")
    @Indexed
    private String direction; // BUY, SELL, HOLD

    @Field("strength")
    private BigDecimal strength;

    @Field("probability")
    private BigDecimal probability;

    @Field("expires_at")
    private LocalDateTime expiresAt;

    @Field("is_active")
    @Indexed
    private Boolean isActive = true;

    @Field("source_model")
    private String sourceModel;

    @Field("feature_vector")
    private Map<String, BigDecimal> featureVector;

    @Field("supporting_indicators")
    private Map<String, BigDecimal> supportingIndicators;

    @Field("conditions_met")
    private List<String> conditionsMet;

    @Field("risk_metrics")
    private RiskMetrics riskMetrics;

    @Field("backtest_performance")
    private BacktestPerformance backtestPerformance;

    @Field("metadata")
    private Map<String, Object> metadata;

    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    // Constructors
    public MarketSignalEntity() {
    }

    public MarketSignalEntity(String instrumentKey, String signalType, BigDecimal signalValue,
                              BigDecimal confidence, LocalDateTime timestamp) {
        this.instrumentKey = instrumentKey;
        this.signalType = signalType;
        this.signalValue = signalValue;
        this.confidence = confidence;
        this.timestamp = timestamp;
        this.isActive = true;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getInstrumentKey() {
        return instrumentKey;
    }

    public void setInstrumentKey(String instrumentKey) {
        this.instrumentKey = instrumentKey;
    }

    public String getSignalType() {
        return signalType;
    }

    public void setSignalType(String signalType) {
        this.signalType = signalType;
    }

    public BigDecimal getSignalValue() {
        return signalValue;
    }

    public void setSignalValue(BigDecimal signalValue) {
        this.signalValue = signalValue;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public BigDecimal getStrength() {
        return strength;
    }

    public void setStrength(BigDecimal strength) {
        this.strength = strength;
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public void setProbability(BigDecimal probability) {
        this.probability = probability;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public String getSourceModel() {
        return sourceModel;
    }

    public void setSourceModel(String sourceModel) {
        this.sourceModel = sourceModel;
    }

    public Map<String, BigDecimal> getFeatureVector() {
        return featureVector;
    }

    public void setFeatureVector(Map<String, BigDecimal> featureVector) {
        this.featureVector = featureVector;
    }

    public Map<String, BigDecimal> getSupportingIndicators() {
        return supportingIndicators;
    }

    public void setSupportingIndicators(Map<String, BigDecimal> supportingIndicators) {
        this.supportingIndicators = supportingIndicators;
    }

    public List<String> getConditionsMet() {
        return conditionsMet;
    }

    public void setConditionsMet(List<String> conditionsMet) {
        this.conditionsMet = conditionsMet;
    }

    public RiskMetrics getRiskMetrics() {
        return riskMetrics;
    }

    public void setRiskMetrics(RiskMetrics riskMetrics) {
        this.riskMetrics = riskMetrics;
    }

    public BacktestPerformance getBacktestPerformance() {
        return backtestPerformance;
    }

    public void setBacktestPerformance(BacktestPerformance backtestPerformance) {
        this.backtestPerformance = backtestPerformance;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // Business Methods
    public boolean isHighConfidence() {
        return confidence != null && confidence.compareTo(BigDecimal.valueOf(0.8)) >= 0;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isActionable() {
        return Boolean.TRUE.equals(isActive) &&
                !isExpired() &&
                isHighConfidence() &&
                strength != null &&
                strength.compareTo(BigDecimal.valueOf(0.6)) >= 0;
    }

    public void deactivate() {
        this.isActive = false;
    }

    // Embedded documents
    public static class RiskMetrics {
        @Field("var_95")
        private BigDecimal var95;

        @Field("max_drawdown")
        private BigDecimal maxDrawdown;

        @Field("sharpe_ratio")
        private BigDecimal sharpeRatio;

        @Field("volatility")
        private BigDecimal volatility;

        // Constructors, getters, setters
        public RiskMetrics() {
        }

        public BigDecimal getVar95() {
            return var95;
        }

        public void setVar95(BigDecimal var95) {
            this.var95 = var95;
        }

        public BigDecimal getMaxDrawdown() {
            return maxDrawdown;
        }

        public void setMaxDrawdown(BigDecimal maxDrawdown) {
            this.maxDrawdown = maxDrawdown;
        }

        public BigDecimal getSharpeRatio() {
            return sharpeRatio;
        }

        public void setSharpeRatio(BigDecimal sharpeRatio) {
            this.sharpeRatio = sharpeRatio;
        }

        public BigDecimal getVolatility() {
            return volatility;
        }

        public void setVolatility(BigDecimal volatility) {
            this.volatility = volatility;
        }
    }

    public static class BacktestPerformance {
        @Field("win_rate")
        private BigDecimal winRate;

        @Field("avg_return")
        private BigDecimal avgReturn;

        @Field("total_trades")
        private Integer totalTrades;

        @Field("profit_factor")
        private BigDecimal profitFactor;

        // Constructors, getters, setters
        public BacktestPerformance() {
        }

        public BigDecimal getWinRate() {
            return winRate;
        }

        public void setWinRate(BigDecimal winRate) {
            this.winRate = winRate;
        }

        public BigDecimal getAvgReturn() {
            return avgReturn;
        }

        public void setAvgReturn(BigDecimal avgReturn) {
            this.avgReturn = avgReturn;
        }

        public Integer getTotalTrades() {
            return totalTrades;
        }

        public void setTotalTrades(Integer totalTrades) {
            this.totalTrades = totalTrades;
        }

        public BigDecimal getProfitFactor() {
            return profitFactor;
        }

        public void setProfitFactor(BigDecimal profitFactor) {
            this.profitFactor = profitFactor;
        }
    }
}
