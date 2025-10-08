package com.trade.frankenstein.trader.model.documents;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "instrument_ticks")
@CompoundIndex(name = "instrument_timestamp_idx", def = "{'instrumentKey': 1, 'timestamp': -1}")
@CompoundIndex(name = "source_timestamp_idx", def = "{'source': 1, 'timestamp': -1}")
public class InstrumentTickEntity {

    @Id
    private String id;

    @Field("instrument_key")
    @Indexed
    private String instrumentKey;

    @Field("price")
    private BigDecimal price;

    @Field("volume")
    private Long volume;

    @Field("timestamp")
    @Indexed
    private LocalDateTime timestamp;

    @Field("source")
    @Indexed
    private String source;

    @Field("bid_price")
    private BigDecimal bidPrice;

    @Field("ask_price")
    private BigDecimal askPrice;

    @Field("bid_size")
    private BigDecimal bidSize;

    @Field("ask_size")
    private BigDecimal askSize;

    @Field("quality_score")
    @Indexed
    private BigDecimal qualityScore;

    @Field("has_anomaly")
    @Indexed
    private Boolean hasAnomaly = false;

    @Field("anomaly_reason")
    private String anomalyReason;

    @Field("anomalies")
    private java.util.Set<String> anomalies;

    @Field("latency_ms")
    private Long latencyMs;

    @Field("validation_status")
    private String validationStatus;

    @Field("metadata")
    private Map<String, Object> metadata;

    // MongoDB-specific fields
    @Field("exchange")
    private String exchange;

    @Field("symbol_type")
    private String symbolType;

    @Field("tick_size")
    private BigDecimal tickSize;

    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Field("updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public InstrumentTickEntity() {
    }

    public InstrumentTickEntity(String instrumentKey, BigDecimal price, Long volume,
                                LocalDateTime timestamp, String source) {
        this.instrumentKey = instrumentKey;
        this.price = price;
        this.volume = volume;
        this.timestamp = timestamp;
        this.source = source;
        this.qualityScore = BigDecimal.ONE;
        this.validationStatus = "PENDING";
        this.hasAnomaly = false;
        this.anomalies = new java.util.HashSet<>();
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public BigDecimal getBidPrice() {
        return bidPrice;
    }

    public void setBidPrice(BigDecimal bidPrice) {
        this.bidPrice = bidPrice;
    }

    public BigDecimal getAskPrice() {
        return askPrice;
    }

    public void setAskPrice(BigDecimal askPrice) {
        this.askPrice = askPrice;
    }

    public BigDecimal getBidSize() {
        return bidSize;
    }

    public void setBidSize(BigDecimal bidSize) {
        this.bidSize = bidSize;
    }

    public BigDecimal getAskSize() {
        return askSize;
    }

    public void setAskSize(BigDecimal askSize) {
        this.askSize = askSize;
    }

    public BigDecimal getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(BigDecimal qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Boolean getHasAnomaly() {
        return hasAnomaly;
    }

    public void setHasAnomaly(Boolean hasAnomaly) {
        this.hasAnomaly = hasAnomaly;
    }

    public String getAnomalyReason() {
        return anomalyReason;
    }

    public void setAnomalyReason(String anomalyReason) {
        this.anomalyReason = anomalyReason;
    }

    public java.util.Set<String> getAnomalies() {
        return anomalies;
    }

    public void setAnomalies(java.util.Set<String> anomalies) {
        this.anomalies = anomalies;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(String validationStatus) {
        this.validationStatus = validationStatus;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getSymbolType() {
        return symbolType;
    }

    public void setSymbolType(String symbolType) {
        this.symbolType = symbolType;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public void setTickSize(BigDecimal tickSize) {
        this.tickSize = tickSize;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Business Methods
    public BigDecimal getSpread() {
        if (bidPrice != null && askPrice != null) {
            return askPrice.subtract(bidPrice);
        }
        return BigDecimal.ZERO;
    }

    public boolean isHighQuality() {
        return qualityScore != null &&
                qualityScore.compareTo(BigDecimal.valueOf(0.9)) >= 0 &&
                !Boolean.TRUE.equals(hasAnomaly);
    }

    public void markAsAnomaly(String reason) {
        this.hasAnomaly = true;
        this.anomalyReason = reason;
        if (this.anomalies == null) {
            this.anomalies = new java.util.HashSet<>();
        }
        this.anomalies.add(reason);
        this.qualityScore = BigDecimal.valueOf(0.1);
        this.validationStatus = "ANOMALY";
    }

    public void addMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new java.util.HashMap<>();
        }
        this.metadata.put(key, value);
    }
}
