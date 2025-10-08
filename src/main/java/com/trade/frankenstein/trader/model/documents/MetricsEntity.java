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

@Document(collection = "metrics")
@CompoundIndex(name = "metric_name_timestamp_idx", def = "{'metricName': 1, 'timestamp': -1}")
public class MetricsEntity {

    @Id
    private String id;

    @Field("metric_name")
    @Indexed
    private String metricName;

    @Field("metric_type")
    @Indexed
    private String metricType; // COUNTER, GAUGE, HISTOGRAM

    @Field("value")
    private BigDecimal value;

    @Field("timestamp")
    @Indexed
    private LocalDateTime timestamp;

    @Field("tags")
    private Map<String, String> tags;

    @Field("metadata")
    private Map<String, Object> metadata;

    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    // Constructors
    public MetricsEntity() {
    }

    public MetricsEntity(String metricName, String metricType, BigDecimal value, LocalDateTime timestamp) {
        this.metricName = metricName;
        this.metricType = metricType;
        this.value = value;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
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
}
