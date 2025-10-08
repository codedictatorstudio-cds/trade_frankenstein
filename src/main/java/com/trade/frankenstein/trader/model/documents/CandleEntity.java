package com.trade.frankenstein.trader.model.documents;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "candles")
@CompoundIndex(name = "instrument_timeframe_timestamp_idx",
        def = "{'instrumentKey': 1, 'timeframe': 1, 'timestamp': -1}")
public class CandleEntity {

    @Id
    private String id;

    @Field("instrument_key")
    @Indexed
    private String instrumentKey;

    @Field("open")
    private BigDecimal open;

    @Field("high")
    private BigDecimal high;

    @Field("low")
    private BigDecimal low;

    @Field("close")
    private BigDecimal close;

    @Field("volume")
    private Long volume;

    @Field("timestamp")
    @Indexed
    private LocalDateTime timestamp;

    @Field("timeframe")
    @Indexed
    private String timeframe;

    @Field("source")
    private String source;

    @Field("vwap")
    private BigDecimal vwap;

    @Field("trade_count")
    private Integer tradeCount;

    @Field("quality_score")
    private BigDecimal qualityScore;

    @Field("has_gaps")
    private Boolean hasGaps = false;

    @Field("is_complete")
    private Boolean isComplete = true;

    @Field("metadata")
    private Map<String, Object> metadata;

    // Technical indicators (embedded for performance)
    @Field("technical_indicators")
    private TechnicalIndicators technicalIndicators;

    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    // Constructors
    public CandleEntity() {}

    public CandleEntity(String instrumentKey, BigDecimal open, BigDecimal high, BigDecimal low,
                        BigDecimal close, Long volume, LocalDateTime timestamp, String timeframe) {
        this.instrumentKey = instrumentKey;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
        this.timeframe = timeframe;
        this.qualityScore = BigDecimal.ONE;
        this.isComplete = true;
        this.hasGaps = false;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getInstrumentKey() { return instrumentKey; }
    public void setInstrumentKey(String instrumentKey) { this.instrumentKey = instrumentKey; }

    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public BigDecimal getVwap() { return vwap; }
    public void setVwap(BigDecimal vwap) { this.vwap = vwap; }

    public Integer getTradeCount() { return tradeCount; }
    public void setTradeCount(Integer tradeCount) { this.tradeCount = tradeCount; }

    public BigDecimal getQualityScore() { return qualityScore; }
    public void setQualityScore(BigDecimal qualityScore) { this.qualityScore = qualityScore; }

    public Boolean getHasGaps() { return hasGaps; }
    public void setHasGaps(Boolean hasGaps) { this.hasGaps = hasGaps; }

    public Boolean getIsComplete() { return isComplete; }
    public void setIsComplete(Boolean isComplete) { this.isComplete = isComplete; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public TechnicalIndicators getTechnicalIndicators() { return technicalIndicators; }
    public void setTechnicalIndicators(TechnicalIndicators technicalIndicators) {
        this.technicalIndicators = technicalIndicators;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // Business Methods
    public BigDecimal getRange() {
        return high != null && low != null ? high.subtract(low) : BigDecimal.ZERO;
    }

    public BigDecimal getBodySize() {
        return close != null && open != null ? close.subtract(open).abs() : BigDecimal.ZERO;
    }

    public boolean isBullish() {
        return close != null && open != null && close.compareTo(open) > 0;
    }

    public boolean isBearish() {
        return close != null && open != null && close.compareTo(open) < 0;
    }

    public boolean isDoji() {
        if (getRange().compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal bodyPercent = getBodySize().divide(getRange(), 4, java.math.RoundingMode.HALF_UP);
        return bodyPercent.compareTo(BigDecimal.valueOf(0.1)) <= 0;
    }

    // Embedded document for technical indicators
    public static class TechnicalIndicators {
        @Field("sma_20")
        private BigDecimal sma20;

        @Field("ema_12")
        private BigDecimal ema12;

        @Field("ema_26")
        private BigDecimal ema26;

        @Field("rsi")
        private BigDecimal rsi;

        @Field("macd")
        private BigDecimal macd;

        @Field("macd_signal")
        private BigDecimal macdSignal;

        @Field("bollinger_upper")
        private BigDecimal bollingerUpper;

        @Field("bollinger_lower")
        private BigDecimal bollingerLower;

        @Field("atr")
        private BigDecimal atr;

        // Constructors, getters, setters
        public TechnicalIndicators() {}

        public BigDecimal getSma20() { return sma20; }
        public void setSma20(BigDecimal sma20) { this.sma20 = sma20; }

        public BigDecimal getEma12() { return ema12; }
        public void setEma12(BigDecimal ema12) { this.ema12 = ema12; }

        public BigDecimal getEma26() { return ema26; }
        public void setEma26(BigDecimal ema26) { this.ema26 = ema26; }

        public BigDecimal getRsi() { return rsi; }
        public void setRsi(BigDecimal rsi) { this.rsi = rsi; }

        public BigDecimal getMacd() { return macd; }
        public void setMacd(BigDecimal macd) { this.macd = macd; }

        public BigDecimal getMacdSignal() { return macdSignal; }
        public void setMacdSignal(BigDecimal macdSignal) { this.macdSignal = macdSignal; }

        public BigDecimal getBollingerUpper() { return bollingerUpper; }
        public void setBollingerUpper(BigDecimal bollingerUpper) { this.bollingerUpper = bollingerUpper; }

        public BigDecimal getBollingerLower() { return bollingerLower; }
        public void setBollingerLower(BigDecimal bollingerLower) { this.bollingerLower = bollingerLower; }

        public BigDecimal getAtr() { return atr; }
        public void setAtr(BigDecimal atr) { this.atr = atr; }
    }
}
