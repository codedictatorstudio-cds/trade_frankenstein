package com.trade.frankenstein.trader.enums;

/**
 * Enhanced trading strategies with risk profiles and characteristics
 */
public enum StrategyName {

    // Existing strategies with enhanced metadata
    MEAN_REVERSION("Mean reversion strategy", RiskCategory.MEDIUM, 30),
    MOMENTUM("Momentum following", RiskCategory.HIGH, 15),
    BREAKOUT("Breakout trading", RiskCategory.HIGH, 20),
    OPTION_STRADDLE("Options straddle", RiskCategory.MEDIUM, 45),
    OPTION_STRANGLE("Options strangle", RiskCategory.MEDIUM, 45),
    IRON_FLY("Iron fly spread", RiskCategory.LOW, 60),
    IRON_CONDOR("Iron condor spread", RiskCategory.LOW, 60),
    SCALPING("High frequency scalping", RiskCategory.HIGH, 5),
    SWING("Swing trading", RiskCategory.MEDIUM, 240),
    PAIRS("Pairs trading", RiskCategory.LOW, 120),
    STAT_ARB("Statistical arbitrage", RiskCategory.LOW, 30),
    DQS("Decision quality system", RiskCategory.MEDIUM, 60),

    // New risk management strategies
    RISK_MANAGEMENT("Risk management action", RiskCategory.CRITICAL, 1),
    STOP_LOSS("Stop loss execution", RiskCategory.HIGH, 1),
    TAKE_PROFIT("Take profit execution", RiskCategory.LOW, 1),
    PORTFOLIO_HEDGE("Portfolio hedging", RiskCategory.MEDIUM, 240),

    // New algorithmic strategies
    GRID_TRADING("Grid trading system", RiskCategory.MEDIUM, 60),
    MARTINGALE("Martingale system", RiskCategory.HIGH, 30),
    ANTI_MARTINGALE("Anti-martingale system", RiskCategory.MEDIUM, 30),
    BOLLINGER_BANDS("Bollinger bands strategy", RiskCategory.MEDIUM, 45),
    RSI_DIVERGENCE("RSI divergence", RiskCategory.MEDIUM, 30),
    MACD_SIGNAL("MACD signal", RiskCategory.MEDIUM, 25),

    // Market condition specific
    VOLATILITY_EXPANSION("High volatility expansion", RiskCategory.HIGH, 15),
    VOLATILITY_CONTRACTION("Low volatility contraction", RiskCategory.LOW, 120),
    NEWS_REACTION("News-based reaction", RiskCategory.HIGH, 10),
    EARNINGS_PLAY("Earnings announcement play", RiskCategory.HIGH, 480),

    // Machine learning strategies
    ML_PREDICTION("ML prediction model", RiskCategory.MEDIUM, 60),
    SENTIMENT_BASED("Sentiment analysis based", RiskCategory.MEDIUM, 30),
    PATTERN_RECOGNITION("Chart pattern recognition", RiskCategory.MEDIUM, 45);

    private final String description;
    private final RiskCategory defaultRiskCategory;
    private final int typicalHoldingMinutes;

    StrategyName(String description, RiskCategory defaultRiskCategory, int typicalHoldingMinutes) {
        this.description = description;
        this.defaultRiskCategory = defaultRiskCategory;
        this.typicalHoldingMinutes = typicalHoldingMinutes;
    }

    public String getDescription() {
        return description;
    }

    public RiskCategory getDefaultRiskCategory() {
        return defaultRiskCategory;
    }

    public int getTypicalHoldingMinutes() {
        return typicalHoldingMinutes;
    }

    public boolean isHighFrequency() {
        return typicalHoldingMinutes <= 10;
    }

    public boolean isRiskStrategy() {
        return this == RISK_MANAGEMENT || this == STOP_LOSS || this == TAKE_PROFIT ||
                this == PORTFOLIO_HEDGE;
    }

    public boolean isOptionsStrategy() {
        return this == OPTION_STRADDLE || this == OPTION_STRANGLE ||
                this == IRON_FLY || this == IRON_CONDOR;
    }
}
