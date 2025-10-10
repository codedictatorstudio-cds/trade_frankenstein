package com.trade.frankenstein.trader.service.market;

import com.trade.frankenstein.trader.dto.AlertDTO;
import com.trade.frankenstein.trader.model.documents.AlertEntity;
import com.trade.frankenstein.trader.repo.documents.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AlertService {

    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // In-memory subscriptions for real-time alerts
    private final Map<String, Set<Consumer<AlertDTO>>> instrumentSubscriptions = new ConcurrentHashMap<>();
    private final Map<AlertDTO.AlertType, Set<Consumer<AlertDTO>>> typeSubscriptions = new ConcurrentHashMap<>();
    private final Map<AlertDTO.AlertSeverity, Set<Consumer<AlertDTO>>> severitySubscriptions = new ConcurrentHashMap<>();
    private final Set<Consumer<AlertDTO>> globalSubscriptions = ConcurrentHashMap.newKeySet();

    // Alert deduplication cache
    private final Map<String, Instant> recentAlerts = new ConcurrentHashMap<>();
    private static final int DEDUPLICATION_WINDOW_MINUTES = 5;

    /**
     * Send an alert with full processing pipeline
     */
    public void sendAlert(AlertDTO alert) {
        try {
            logger.debug("Processing alert: {} for instrument: {}", alert.type(), alert.instrumentKey());

            // Validate alert
            if (!isValidAlert(alert)) {
                logger.warn("Invalid alert received: {}", alert);
                return;
            }

            // Check for deduplication
            if (isDuplicateAlert(alert)) {
                logger.debug("Duplicate alert suppressed: {}", alert.message());
                return;
            }

            // Persist alert
            AlertEntity savedAlert = persistAlert(alert);

            // Create enriched alert DTO
            AlertDTO enrichedAlert = enrichAlert(alert, savedAlert.getId());

            // Process alert asynchronously
            processAlertAsync(enrichedAlert);

            // Update deduplication cache
            updateDeduplicationCache(enrichedAlert);

            logger.info("Alert processed successfully: {} - {}", enrichedAlert.type(), enrichedAlert.message());

        } catch (Exception e) {
            logger.error("Error processing alert: {}", e.getMessage(), e);
        }
    }

    /**
     * Send critical alert with immediate processing
     */
    public void sendCriticalAlert(AlertDTO alert) {
        AlertDTO criticalAlert = new AlertDTO(
                alert.alertId(),
                alert.type(),
                AlertDTO.AlertSeverity.CRITICAL,
                alert.instrumentKey(),
                "[CRITICAL] " + alert.message(),
                alert.timestamp(),
                alert.source(),
                alert.context(),
                alert.acknowledged(),
                alert.acknowledgedBy(),
                alert.acknowledgedAt()
        );

        sendAlert(criticalAlert);
    }

    /**
     * Subscribe to alerts for a specific instrument
     */
    public void subscribeToInstrument(String instrumentKey, Consumer<AlertDTO> callback) {
        instrumentSubscriptions.computeIfAbsent(instrumentKey, k -> ConcurrentHashMap.newKeySet())
                .add(callback);
        logger.info("Added subscription for instrument: {}", instrumentKey);
    }

    /**
     * Subscribe to alerts by type
     */
    public void subscribeToType(AlertDTO.AlertType alertType, Consumer<AlertDTO> callback) {
        typeSubscriptions.computeIfAbsent(alertType, k -> ConcurrentHashMap.newKeySet())
                .add(callback);
        logger.info("Added subscription for alert type: {}", alertType);
    }

    /**
     * Subscribe to alerts by severity
     */
    public void subscribeToSeverity(AlertDTO.AlertSeverity severity, Consumer<AlertDTO> callback) {
        severitySubscriptions.computeIfAbsent(severity, k -> ConcurrentHashMap.newKeySet())
                .add(callback);
        logger.info("Added subscription for severity: {}", severity);
    }

    /**
     * Subscribe to all alerts
     */
    public void subscribeToAll(Consumer<AlertDTO> callback) {
        globalSubscriptions.add(callback);
        logger.info("Added global alert subscription");
    }

    /**
     * Unsubscribe from instrument alerts
     */
    public void unsubscribeFromInstrument(String instrumentKey, Consumer<AlertDTO> callback) {
        Set<Consumer<AlertDTO>> subscribers = instrumentSubscriptions.get(instrumentKey);
        if (subscribers != null) {
            subscribers.remove(callback);
            if (subscribers.isEmpty()) {
                instrumentSubscriptions.remove(instrumentKey);
            }
        }
    }

    /**
     * Acknowledge an alert
     */
    public boolean acknowledgeAlert(String alertId, String acknowledgedBy) {
        try {
            Optional<AlertEntity> alertOpt = alertRepository.findById(alertId);
            if (alertOpt.isPresent()) {
                AlertEntity alert = alertOpt.get();
                alert.setAcknowledged(true);
                alert.setAcknowledgedBy(acknowledgedBy);
                alert.setAcknowledgedAt(Instant.now());
                alertRepository.save(alert);

                logger.info("Alert acknowledged: {} by {}", alertId, acknowledgedBy);
                return true;
            }
        } catch (Exception e) {
            logger.error("Error acknowledging alert {}: {}", alertId, e.getMessage());
        }
        return false;
    }

    /**
     * Get unacknowledged alerts
     */
    public List<AlertDTO> getUnacknowledgedAlerts() {
        return alertRepository.findByAcknowledgedFalseOrderByTimestampDesc()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get alerts for specific instrument
     */
    public List<AlertDTO> getAlertsForInstrument(String instrumentKey, int limit) {
        return alertRepository.findByInstrumentKeyOrderByTimestampDesc(instrumentKey)
                .stream()
                .limit(limit)
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get alerts by severity
     */
    public List<AlertDTO> getAlertsBySeverity(AlertDTO.AlertSeverity severity, Instant since) {
        return alertRepository.findBySeverityAndTimestampAfterOrderByTimestampDesc(
                        severity.name(), since)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get critical unacknowledged alerts
     */
    public List<AlertDTO> getCriticalUnacknowledgedAlerts() {
        return alertRepository.findBySeverityAndAcknowledgedFalseOrderByTimestampDesc("CRITICAL")
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get alert statistics
     */
    public AlertStatistics getAlertStatistics(Instant since) {
        List<AlertEntity> alerts = alertRepository.findByTimestampAfter(since);

        long totalAlerts = alerts.size();
        long acknowledgedAlerts = alerts.stream().mapToLong(a -> a.getAcknowledged() ? 1 : 0).sum();
        long criticalAlerts = alerts.stream().mapToLong(a -> "CRITICAL".equals(a.getSeverity()) ? 1 : 0).sum();

        Map<String, Long> typeBreakdown = alerts.stream()
                .collect(Collectors.groupingBy(AlertEntity::getType, Collectors.counting()));

        Map<String, Long> severityBreakdown = alerts.stream()
                .collect(Collectors.groupingBy(AlertEntity::getSeverity, Collectors.counting()));

        return new AlertStatistics(totalAlerts, acknowledgedAlerts, criticalAlerts,
                typeBreakdown, severityBreakdown);
    }

    /**
     * Clean up old acknowledged alerts
     */
    public int cleanupOldAlerts(int daysOld) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(daysOld));
        List<AlertEntity> oldAlerts = alertRepository.findByAcknowledgedTrueAndTimestampBefore(cutoff);
        alertRepository.deleteAll(oldAlerts);

        logger.info("Cleaned up {} old acknowledged alerts", oldAlerts.size());
        return oldAlerts.size();
    }

    // Private helper methods

    @Async
    private void processAlertAsync(AlertDTO alert) {
        try {

            // Broadcast to subscribers
            broadcastToSubscribers(alert);

            // Publish application event
            eventPublisher.publishEvent(new AlertEvent(alert));

            // Handle escalation for critical alerts
            if (alert.severity() == AlertDTO.AlertSeverity.CRITICAL) {
                handleCriticalAlertEscalation(alert);
            }

        } catch (Exception e) {
            logger.error("Error in async alert processing: {}", e.getMessage(), e);
        }
    }

    private void broadcastToSubscribers(AlertDTO alert) {
        // Global subscribers
        globalSubscriptions.forEach(subscriber -> {
            try {
                subscriber.accept(alert);
            } catch (Exception e) {
                logger.warn("Error notifying global subscriber: {}", e.getMessage());
            }
        });

        // Instrument-specific subscribers
        if (alert.instrumentKey() != null) {
            Set<Consumer<AlertDTO>> instrumentSubs = instrumentSubscriptions.get(alert.instrumentKey());
            if (instrumentSubs != null) {
                instrumentSubs.forEach(subscriber -> {
                    try {
                        subscriber.accept(alert);
                    } catch (Exception e) {
                        logger.warn("Error notifying instrument subscriber: {}", e.getMessage());
                    }
                });
            }
        }

        // Type-specific subscribers
        Set<Consumer<AlertDTO>> typeSubs = typeSubscriptions.get(alert.type());
        if (typeSubs != null) {
            typeSubs.forEach(subscriber -> {
                try {
                    subscriber.accept(alert);
                } catch (Exception e) {
                    logger.warn("Error notifying type subscriber: {}", e.getMessage());
                }
            });
        }

        // Severity-specific subscribers
        Set<Consumer<AlertDTO>> severitySubs = severitySubscriptions.get(alert.severity());
        if (severitySubs != null) {
            severitySubs.forEach(subscriber -> {
                try {
                    subscriber.accept(alert);
                } catch (Exception e) {
                    logger.warn("Error notifying severity subscriber: {}", e.getMessage());
                }
            });
        }
    }

    private boolean isValidAlert(AlertDTO alert) {
        return alert != null &&
                alert.type() != null &&
                alert.severity() != null &&
                alert.message() != null &&
                !alert.message().trim().isEmpty() &&
                alert.timestamp() != null;
    }

    private boolean isDuplicateAlert(AlertDTO alert) {
        String alertKey = generateAlertKey(alert);
        Instant lastAlert = recentAlerts.get(alertKey);

        if (lastAlert != null) {
            return lastAlert.isAfter(Instant.now().minus(Duration.ofMinutes(DEDUPLICATION_WINDOW_MINUTES)));
        }

        return false;
    }

    private String generateAlertKey(AlertDTO alert) {
        return String.format("%s:%s:%s:%s",
                alert.type(),
                alert.instrumentKey() != null ? alert.instrumentKey() : "null",
                alert.severity(),
                alert.message().hashCode());
    }

    private void updateDeduplicationCache(AlertDTO alert) {
        String alertKey = generateAlertKey(alert);
        recentAlerts.put(alertKey, alert.timestamp());

        // Cleanup old entries
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(DEDUPLICATION_WINDOW_MINUTES * 2));
        recentAlerts.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private AlertEntity persistAlert(AlertDTO alert) {
        AlertEntity entity = new AlertEntity();
        entity.setAlertId(alert.alertId() != null ? alert.alertId() : UUID.randomUUID().toString());
        entity.setType(alert.type().name());
        entity.setSeverity(alert.severity().name());
        entity.setInstrumentKey(alert.instrumentKey());
        entity.setMessage(alert.message());
        entity.setTimestamp(alert.timestamp());
        entity.setSource(alert.source());
        entity.setContext(alert.context());
        entity.setAcknowledged(alert.acknowledged());
        entity.setAcknowledgedBy(alert.acknowledgedBy());
        entity.setAcknowledgedAt(alert.acknowledgedAt());

        return alertRepository.save(entity);
    }

    private AlertDTO enrichAlert(AlertDTO alert, String persistedId) {
        Map<String, Object> enrichedContext = new HashMap<>();
        if (alert.context() != null) {
            enrichedContext.putAll(alert.context());
        }
        enrichedContext.put("persistent_id", persistedId);
        enrichedContext.put("processed_at", Instant.now());

        return new AlertDTO(
                persistedId,
                alert.type(),
                alert.severity(),
                alert.instrumentKey(),
                alert.message(),
                alert.timestamp(),
                alert.source(),
                enrichedContext,
                alert.acknowledged(),
                alert.acknowledgedBy(),
                alert.acknowledgedAt()
        );
    }

    private void handleCriticalAlertEscalation(AlertDTO alert) {
        // Implement escalation logic for critical alerts
        logger.warn("CRITICAL ALERT ESCALATION: {} - {}", alert.type(), alert.message());

        // Could implement additional escalation like:
        // - SMS to on-call personnel
        // - Slack/Teams urgent notifications
        // - Dashboard highlighting
        // - Auto-pause trading if system critical
    }

    private AlertDTO convertToDTO(AlertEntity entity) {
        return new AlertDTO(
                entity.getAlertId(),
                AlertDTO.AlertType.valueOf(entity.getType()),
                AlertDTO.AlertSeverity.valueOf(entity.getSeverity()),
                entity.getInstrumentKey(),
                entity.getMessage(),
                entity.getTimestamp(),
                entity.getSource(),
                entity.getContext(),
                entity.getAcknowledged(),
                entity.getAcknowledgedBy(),
                entity.getAcknowledgedAt()
        );
    }

    // Inner classes and events
        public record AlertEvent(AlertDTO alert) {
    }

    public record AlertStatistics(
            long totalAlerts,
            long acknowledgedAlerts,
            long criticalAlerts,
            Map<String, Long> typeBreakdown,
            Map<String, Long> severityBreakdown
    ) {
    }
}
