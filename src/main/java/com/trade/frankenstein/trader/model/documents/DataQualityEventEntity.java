package com.trade.frankenstein.trader.model.documents;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "data_quality_events")
public class DataQualityEventEntity {

    @Id
    private String id;

    @Field("instrument_key")
    @Indexed
    private String instrumentKey;

    @Field("event_type")
    @Indexed
    private String eventType;

    @Field("severity")
    @Indexed
    private String severity; // LOW, MEDIUM, HIGH, CRITICAL

    @Field("description")
    private String description;

    @Field("timestamp")
    @Indexed
    private LocalDateTime timestamp;

    @Field("source")
    private String source;

    @Field("affected_data_points")
    private Integer affectedDataPoints;

    @Field("quality_impact")
    private String qualityImpact;

    @Field("anomaly_details")
    private Map<String, Object> anomalyDetails;

    @Field("resolution_actions")
    private List<String> resolutionActions;

    @Field("is_resolved")
    @Indexed
    private Boolean isResolved = false;

    @Field("resolved_at")
    private LocalDateTime resolvedAt;

    @Field("resolved_by")
    private String resolvedBy;

    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    // Constructors
    public DataQualityEventEntity() {
    }

    public DataQualityEventEntity(String instrumentKey, String eventType, String severity,
                                  String description, LocalDateTime timestamp) {
        this.instrumentKey = instrumentKey;
        this.eventType = eventType;
        this.severity = severity;
        this.description = description;
        this.timestamp = timestamp;
        this.isResolved = false;
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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public Integer getAffectedDataPoints() {
        return affectedDataPoints;
    }

    public void setAffectedDataPoints(Integer affectedDataPoints) {
        this.affectedDataPoints = affectedDataPoints;
    }

    public String getQualityImpact() {
        return qualityImpact;
    }

    public void setQualityImpact(String qualityImpact) {
        this.qualityImpact = qualityImpact;
    }

    public Map<String, Object> getAnomalyDetails() {
        return anomalyDetails;
    }

    public void setAnomalyDetails(Map<String, Object> anomalyDetails) {
        this.anomalyDetails = anomalyDetails;
    }

    public List<String> getResolutionActions() {
        return resolutionActions;
    }

    public void setResolutionActions(List<String> resolutionActions) {
        this.resolutionActions = resolutionActions;
    }

    public Boolean getIsResolved() {
        return isResolved;
    }

    public void setIsResolved(Boolean isResolved) {
        this.isResolved = isResolved;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // Business Methods
    public boolean isCritical() {
        return "CRITICAL".equals(severity);
    }

    public void resolve(String resolvedBy) {
        this.isResolved = true;
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = resolvedBy;
    }

    public boolean isOld() {
        return timestamp.isBefore(LocalDateTime.now().minusDays(7));
    }
}
