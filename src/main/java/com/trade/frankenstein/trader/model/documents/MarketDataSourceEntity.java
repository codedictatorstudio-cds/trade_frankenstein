package com.trade.frankenstein.trader.model.documents;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "market_data_sources")
public class MarketDataSourceEntity {

    @Id
    private String id;

    @Field("source_id")
    @Indexed(unique = true)
    private String sourceId;

    @Field("source_name")
    private String sourceName;

    @Field("api_endpoint")
    private String apiEndpoint;

    @Field("websocket_endpoint")
    private String websocketEndpoint;

    @Field("priority")
    @Indexed
    private Integer priority; // 1 = highest priority

    @Field("enabled")
    @Indexed
    private Boolean enabled = true;

    @Field("credentials")
    private Map<String, String> credentials;

    @Field("supported_instruments")
    private List<String> supportedInstruments;

    @Field("supported_timeframes")
    private List<String> supportedTimeframes;

    @Field("rate_limits")
    private RateLimits rateLimits;

    @Field("health_check_url")
    private String healthCheckUrl;

    @Field("health_check_interval_seconds")
    private Integer healthCheckIntervalSeconds;

    @Field("last_health_check")
    private LocalDateTime lastHealthCheck;

    @Field("is_healthy")
    @Indexed
    private Boolean isHealthy = true;

    @Field("failure_count")
    private Integer failureCount = 0;

    @Field("last_failure")
    private LocalDateTime lastFailure;

    @Field("configuration")
    private Map<String, Object> configuration;

    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Field("updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public MarketDataSourceEntity() {
    }

    public MarketDataSourceEntity(String sourceId, String sourceName, String apiEndpoint, Integer priority) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.apiEndpoint = apiEndpoint;
        this.priority = priority;
        this.enabled = true;
        this.isHealthy = true;
        this.failureCount = 0;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getWebsocketEndpoint() {
        return websocketEndpoint;
    }

    public void setWebsocketEndpoint(String websocketEndpoint) {
        this.websocketEndpoint = websocketEndpoint;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getCredentials() {
        return credentials;
    }

    public void setCredentials(Map<String, String> credentials) {
        this.credentials = credentials;
    }

    public List<String> getSupportedInstruments() {
        return supportedInstruments;
    }

    public void setSupportedInstruments(List<String> supportedInstruments) {
        this.supportedInstruments = supportedInstruments;
    }

    public List<String> getSupportedTimeframes() {
        return supportedTimeframes;
    }

    public void setSupportedTimeframes(List<String> supportedTimeframes) {
        this.supportedTimeframes = supportedTimeframes;
    }

    public RateLimits getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(RateLimits rateLimits) {
        this.rateLimits = rateLimits;
    }

    public String getHealthCheckUrl() {
        return healthCheckUrl;
    }

    public void setHealthCheckUrl(String healthCheckUrl) {
        this.healthCheckUrl = healthCheckUrl;
    }

    public Integer getHealthCheckIntervalSeconds() {
        return healthCheckIntervalSeconds;
    }

    public void setHealthCheckIntervalSeconds(Integer healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }

    public LocalDateTime getLastHealthCheck() {
        return lastHealthCheck;
    }

    public void setLastHealthCheck(LocalDateTime lastHealthCheck) {
        this.lastHealthCheck = lastHealthCheck;
    }

    public Boolean getIsHealthy() {
        return isHealthy;
    }

    public void setIsHealthy(Boolean isHealthy) {
        this.isHealthy = isHealthy;
    }

    public Integer getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(Integer failureCount) {
        this.failureCount = failureCount;
    }

    public LocalDateTime getLastFailure() {
        return lastFailure;
    }

    public void setLastFailure(LocalDateTime lastFailure) {
        this.lastFailure = lastFailure;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
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
    public void recordFailure() {
        this.failureCount = (this.failureCount == null ? 0 : this.failureCount) + 1;
        this.lastFailure = LocalDateTime.now();

        if (this.failureCount >= 5) {
            this.isHealthy = false;
        }
    }

    public void recordSuccess() {
        this.failureCount = 0;
        this.isHealthy = true;
        this.lastHealthCheck = LocalDateTime.now();
    }

    public boolean supportsInstrument(String instrumentKey) {
        return supportedInstruments == null || supportedInstruments.contains(instrumentKey);
    }

    public boolean supportsTimeframe(String timeframe) {
        return supportedTimeframes == null || supportedTimeframes.contains(timeframe);
    }

    // Embedded document for rate limits
    public static class RateLimits {
        @Field("requests_per_second")
        private Integer requestsPerSecond;

        @Field("requests_per_minute")
        private Integer requestsPerMinute;

        @Field("requests_per_hour")
        private Integer requestsPerHour;

        @Field("concurrent_connections")
        private Integer concurrentConnections;

        // Constructors, getters, setters
        public RateLimits() {
        }

        public Integer getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(Integer requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public Integer getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(Integer requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public Integer getRequestsPerHour() {
            return requestsPerHour;
        }

        public void setRequestsPerHour(Integer requestsPerHour) {
            this.requestsPerHour = requestsPerHour;
        }

        public Integer getConcurrentConnections() {
            return concurrentConnections;
        }

        public void setConcurrentConnections(Integer concurrentConnections) {
            this.concurrentConnections = concurrentConnections;
        }
    }
}
