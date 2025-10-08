package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.dto.AlertDTO;
import com.trade.frankenstein.trader.model.documents.RiskEvent;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.repo.documents.RiskEventRepo;
import com.trade.frankenstein.trader.repo.documents.RiskSnapshotRepo;
import com.trade.frankenstein.trader.service.PortfolioService;
import com.trade.frankenstein.trader.service.market.AlertService;
import com.trade.frankenstein.trader.service.risk.RiskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RiskMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(RiskMonitorService.class);

    @Autowired
    private RiskService riskService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private FastStateStore fastStateStore;

    @Autowired
    private RiskSnapshotRepo riskSnapshotRepo;

    @Autowired
    private RiskEventRepo riskEventRepo;

    private final AtomicBoolean emergencyKillSwitchActivated = new AtomicBoolean(false);

    // Risk thresholds - configurable
    private static final double MAX_DAILY_LOSS_PCT = 5.0;
    private static final double MAX_POSITION_EXPOSURE = 100000.0;
    private static final int MAX_ORDERS_PER_MINUTE = 60;
    private static final double MIN_RISK_BUDGET_PCT = 10.0;

    /**
     * Check if emergency kill switch is activated
     */
    public boolean isEmergencyKillSwitchActivated() {
        return emergencyKillSwitchActivated.get();
    }

    /**
     * Get overall kill switch status (combines both emergency and risk service kill switches)
     */
    public boolean shouldKillSwitch() {
        return emergencyKillSwitchActivated.get() || riskService.isKillSwitchOpenNew();
    }

    /**
     * Activate emergency kill switch with reason
     */
    public void activateEmergencyKillSwitch(String reason) {
        if (emergencyKillSwitchActivated.compareAndSet(false, true)) {
            logger.error("EMERGENCY KILL SWITCH ACTIVATED: {}", reason);

            // Record risk event
            recordRiskEvent("EMERGENCY_KILL_SWITCH", reason, 0.0, true);

            // Send critical alert
            sendCriticalAlert("Emergency Kill Switch Activated", reason, null);

            // Store in fast state
            fastStateStore.put("emergency_kill_switch_active", "true", Duration.ofMinutes(10));
            fastStateStore.put("emergency_kill_switch_reason", reason, Duration.ofMinutes(10));
            fastStateStore.put("emergency_kill_switch_timestamp", String.valueOf(Instant.now().toEpochMilli()), Duration.ofMinutes(10));

            // Also activate the main risk service kill switch
            riskService.setKillSwitchOpenNew(true, "Emergency activation: " + reason);
        }
    }

    /**
     * Deactivate emergency kill switch
     */
    public void deactivateEmergencyKillSwitch(String reason) {
        if (emergencyKillSwitchActivated.compareAndSet(true, false)) {
            logger.info("Emergency kill switch deactivated: {}", reason);

            // Record risk event
            recordRiskEvent("EMERGENCY_KILL_SWITCH_DEACTIVATED", reason, 0.0, false);

            // Send alert
            sendAlert("Emergency Kill Switch Deactivated", reason, AlertDTO.AlertSeverity.WARNING, null);

            // Remove from fast state
            fastStateStore.delete("emergency_kill_switch_active");
            fastStateStore.delete("emergency_kill_switch_reason");
            fastStateStore.delete("emergency_kill_switch_timestamp");
        }
    }

    /**
     * Update PnL and check for risk violations
     */
    public void updatePnL(String symbol, Double pnl) {
        try {
            // Update daily loss tracking
            riskService.updateDailyLossAbs(pnl < 0 ? pnl.floatValue() : 0f);

            // Get current risk metrics
            RiskSnapshot currentSnapshot = getCurrentRiskSnapshot();

            // Check daily loss limits
            if (currentSnapshot.getDailyLossPct() != null &&
                    currentSnapshot.getDailyLossPct() > MAX_DAILY_LOSS_PCT) {

                activateEmergencyKillSwitch("Daily loss limit exceeded: " +
                        String.format("%.2f%% > %.2f%%", currentSnapshot.getDailyLossPct(), MAX_DAILY_LOSS_PCT));
            }

            // Check risk budget depletion
            if (currentSnapshot.getRiskBudgetLeft() != null &&
                    currentSnapshot.getRiskBudgetLeft() < (portfolioService.getPortfolioSummary().get()
                            .getTotalPnl().doubleValue() * MIN_RISK_BUDGET_PCT / 100)) {

                sendAlert("Risk Budget Low",
                        "Risk budget depleted to: ₹" + currentSnapshot.getRiskBudgetLeft(),
                        AlertDTO.AlertSeverity.HIGH, symbol);
            }

        } catch (Exception e) {
            logger.error("Error updating PnL for symbol {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Get current risk metrics snapshot
     */
    public RiskSnapshot getCurrentRiskSnapshot() {
        try {
            Result<RiskSnapshot> result = riskService.getSummary();
            if (result.isOk()) {
                return result.get();
            }
        } catch (Exception e) {
            logger.error("Error getting risk snapshot: {}", e.getMessage());
        }

        // Return default snapshot if error
        return RiskSnapshot.builder()
                .asOf(Instant.now())
                .riskHeadroomOk(false)
                .killSwitchOpenNew(true)
                .dailyCircuitTripped(true)
                .build();
    }

    /**
     * Get comprehensive risk metrics including custom monitoring data
     */
    public Map<String, Object> getRiskMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        try {
            RiskSnapshot snapshot = getCurrentRiskSnapshot();

            // Basic risk metrics from RiskService
            metrics.put("kill_switch_active", shouldKillSwitch());
            metrics.put("emergency_kill_switch_active", emergencyKillSwitchActivated.get());
            metrics.put("circuit_breaker_active", snapshot.getCircuitBreakerLockout());
            metrics.put("daily_circuit_tripped", snapshot.getDailyCircuitTripped());
            metrics.put("risk_headroom_ok", snapshot.getRiskHeadroomOk());

            // Financial metrics
            metrics.put("daily_loss_pct", snapshot.getDailyLossPct());
            metrics.put("risk_budget_left", snapshot.getRiskBudgetLeft());
            metrics.put("realized_pnl_today", snapshot.getRealizedPnlToday());
            metrics.put("unrealized_pnl_today", snapshot.getUnrealizedPnlToday());

            // Position metrics
            metrics.put("lots_used", snapshot.getLotsUsed());
            metrics.put("lots_cap", snapshot.getLotsCap());

            // Trading activity metrics
            metrics.put("orders_per_min", snapshot.getOrdersPerMin());
            metrics.put("orders_per_min_pct", snapshot.getOrdersPerMinPct());
            metrics.put("minutes_since_last_sl", snapshot.getMinutesSinceLastSl());
            metrics.put("restrikes_today", snapshot.getRestrikesToday());

            // Portfolio exposure by underlying
            addExposureMetrics(metrics);

            // Recent risk events
            metrics.put("recent_risk_events", getRecentRiskEventSummary());

            // Thresholds for reference
            metrics.put("thresholds", getThresholds());

        } catch (Exception e) {
            logger.error("Error building risk metrics: {}", e.getMessage());
            metrics.put("error", "Failed to retrieve risk metrics: " + e.getMessage());
        }

        return metrics;
    }

    /**
     * Scheduled risk monitoring - runs every 30 seconds during market hours
     */
    @Scheduled(cron = "*/30 * 9-15 * * MON-FRI")
    public void scheduledRiskMonitoring() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return;
        }

        try {
            RiskSnapshot snapshot = getCurrentRiskSnapshot();

            // Store snapshot for historical tracking
            riskSnapshotRepo.save(snapshot);

            // Check for various risk violations
            checkDailyLossViolations(snapshot);
            checkPositionExposureViolations();
            checkOrderRateViolations(snapshot);
            checkRiskBudgetViolations(snapshot);

            // Auto-recovery checks
            checkAutoRecoveryConditions(snapshot);

        } catch (Exception e) {
            logger.error("Error in scheduled risk monitoring: {}", e.getMessage());
        }
    }

    /**
     * Check for position exposure violations across underlyings
     */
    private void checkPositionExposureViolations() {
        String[] underlyings = {"NIFTY", "BANKNIFTY", "FINNIFTY"};

        for (String underlying : underlyings) {
            try {
                int openLots = riskService.getOpenLotsForUnderlying(underlying);
                BigDecimal netDelta = riskService.getNetDeltaForUnderlying(underlying);

                // Check lot limits
                if (openLots > RiskConstants.MAX_LOTS) {
                    recordRiskEvent("LOTS_VIOLATION",
                            underlying + " lots exceeded: " + openLots + " > " + RiskConstants.MAX_LOTS,
                            openLots, true);

                    sendAlert("Position Exposure Violation",
                            underlying + " lots exceeded limit: " + openLots,
                            AlertDTO.AlertSeverity.CRITICAL, underlying);
                }

                // Check delta exposure
                if (netDelta.abs().doubleValue() > MAX_POSITION_EXPOSURE) {
                    recordRiskEvent("DELTA_EXPOSURE_VIOLATION",
                            underlying + " delta exposure: " + netDelta,
                            netDelta.doubleValue(), true);

                    sendAlert("Delta Exposure Violation",
                            underlying + " delta exposure exceeded: " + netDelta,
                            AlertDTO.AlertSeverity.CRITICAL, underlying);
                }

            } catch (Exception e) {
                logger.warn("Error checking exposure for {}: {}", underlying, e.getMessage());
            }
        }
    }

    /**
     * Check daily loss violations
     */
    private void checkDailyLossViolations(RiskSnapshot snapshot) {
        if (snapshot.getDailyLossPct() != null && snapshot.getDailyLossPct() > MAX_DAILY_LOSS_PCT) {
            if (!shouldKillSwitch()) {
                activateEmergencyKillSwitch("Daily loss exceeded: " +
                        String.format("%.2f%% > %.2f%%", snapshot.getDailyLossPct(), MAX_DAILY_LOSS_PCT));
            }
        }
    }

    /**
     * Check order rate violations
     */
    private void checkOrderRateViolations(RiskSnapshot snapshot) {
        if (snapshot.getOrdersPerMinPct() != null && snapshot.getOrdersPerMinPct() > 95.0) {
            sendAlert("Order Rate High",
                    "Order rate at " + String.format("%.1f%%", snapshot.getOrdersPerMinPct()) + " of limit",
                    AlertDTO.AlertSeverity.HIGH, null);
        }
    }

    /**
     * Check risk budget violations
     */
    private void checkRiskBudgetViolations(RiskSnapshot snapshot) {
        if (snapshot.getRiskBudgetLeft() != null && snapshot.getRiskBudgetLeft() < 1000.0) {
            sendAlert("Risk Budget Critical",
                    "Risk budget remaining: ₹" + snapshot.getRiskBudgetLeft(),
                    AlertDTO.AlertSeverity.CRITICAL, null);
        }
    }

    /**
     * Check if conditions allow for auto-recovery from kill switch
     */
    private void checkAutoRecoveryConditions(RiskSnapshot snapshot) {
        if (shouldKillSwitch() &&
                snapshot.getDailyLossPct() != null && snapshot.getDailyLossPct() < (MAX_DAILY_LOSS_PCT * 0.8) &&
                snapshot.getRiskBudgetLeft() != null && snapshot.getRiskBudgetLeft() > 5000.0) {

            // Don't auto-recover from emergency kill switch - requires manual intervention
            if (!emergencyKillSwitchActivated.get() && riskService.isKillSwitchOpenNew()) {
                logger.info("Conditions may allow auto-recovery from kill switch");
                sendAlert("Auto-Recovery Possible",
                        "Risk conditions have improved, manual review recommended",
                        AlertDTO.AlertSeverity.INFO, null);
            }
        }
    }

    /**
     * Add exposure metrics to the metrics map
     */
    private void addExposureMetrics(Map<String, Object> metrics) {
        Map<String, Object> exposureMetrics = new HashMap<>();
        String[] underlyings = {"NIFTY", "BANKNIFTY", "FINNIFTY"};

        for (String underlying : underlyings) {
            Map<String, Object> underlyingMetrics = new HashMap<>();
            underlyingMetrics.put("open_lots", riskService.getOpenLotsForUnderlying(underlying));
            underlyingMetrics.put("net_delta", riskService.getNetDeltaForUnderlying(underlying));

            PortfolioService.PortfolioGreeks greeks = riskService.getNetGreeksForUnderlying(underlying);
            underlyingMetrics.put("greeks", Map.of(
                    "delta", greeks.getNetDelta(),
                    "gamma", greeks.getNetGamma(),
                    "theta", greeks.getNetTheta(),
                    "vega", greeks.getNetVega()
            ));

            exposureMetrics.put(underlying.toLowerCase(), underlyingMetrics);
        }

        metrics.put("exposure_by_underlying", exposureMetrics);
    }

    /**
     * Get recent risk events summary
     */
    private List<Map<String, Object>> getRecentRiskEventSummary() {
        List<Map<String, Object>> eventSummary = new ArrayList<>();

        try {
            List<RiskEvent> recentEvents = riskService.getRecentRiskEvents(10);

            for (RiskEvent event : recentEvents) {
                Map<String, Object> eventMap = new HashMap<>();
                eventMap.put("type", event.getType());
                eventMap.put("reason", event.getReason());
                eventMap.put("timestamp", event.getTs());
                eventMap.put("value", event.getValue());
                eventMap.put("breached", event.isBreached());
                eventSummary.add(eventMap);
            }
        } catch (Exception e) {
            logger.warn("Error getting recent risk events: {}", e.getMessage());
        }

        return eventSummary;
    }

    /**
     * Get current risk thresholds
     */
    private Map<String, Object> getThresholds() {
        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("max_daily_loss_pct", MAX_DAILY_LOSS_PCT);
        thresholds.put("max_position_exposure", MAX_POSITION_EXPOSURE);
        thresholds.put("max_orders_per_minute", MAX_ORDERS_PER_MINUTE);
        thresholds.put("min_risk_budget_pct", MIN_RISK_BUDGET_PCT);
        thresholds.put("max_lots", RiskConstants.MAX_LOTS);
        thresholds.put("daily_loss_cap", RiskConstants.DAILY_LOSS_CAP);
        return thresholds;
    }

    /**
     * Record a risk event
     */
    private void recordRiskEvent(String type, String reason, double value, boolean breached) {
        try {
            riskService.recordRiskEvent(type, reason, null, value, breached);
        } catch (Exception e) {
            logger.error("Error recording risk event: {}", e.getMessage());
        }
    }

    /**
     * Send alert using AlertService
     */
    private void sendAlert(String title, String message, AlertDTO.AlertSeverity severity, String instrumentKey) {
        try {
            AlertDTO alert = new AlertDTO(
                    null, // alertId - will be generated
                    AlertDTO.AlertType.RISK_MANAGEMENT,
                    severity,
                    instrumentKey,
                    title + ": " + message,
                    Instant.now(),
                    "RiskMonitorService",
                    Map.of("title", title, "details", message),
                    false,
                    null,
                    null
            );

            alertService.sendAlert(alert);
        } catch (Exception e) {
            logger.error("Error sending alert: {}", e.getMessage());
        }
    }

    /**
     * Send critical alert
     */
    private void sendCriticalAlert(String title, String message, String instrumentKey) {
        sendAlert(title, message, AlertDTO.AlertSeverity.CRITICAL, instrumentKey);
    }

    /**
     * Get kill switch status and details
     */
    public Map<String, Object> getKillSwitchStatus() {
        Map<String, Object> status = new HashMap<>();

        status.put("emergency_kill_switch_active", emergencyKillSwitchActivated.get());
        status.put("risk_service_kill_switch_active", riskService.isKillSwitchOpenNew());
        status.put("overall_kill_switch_active", shouldKillSwitch());

        // Get reason from fast state if available
        Object reason = fastStateStore.get("emergency_kill_switch_reason");
        if (reason != null) {
            status.put("emergency_kill_switch_reason", reason);
        }

        Object timestamp = fastStateStore.get("emergency_kill_switch_timestamp");
        if (timestamp != null) {
            status.put("emergency_kill_switch_timestamp", Long.parseLong(timestamp.toString()));
        }

        return status;
    }

    /**
     * Manual intervention methods for emergency situations
     */
    public void forceKillAllPositions(String reason) {
        logger.error("FORCE KILL ALL POSITIONS: {}", reason);

        activateEmergencyKillSwitch("Force kill initiated: " + reason);

        // Trigger auto-unwind for all underlyings
        String[] underlyings = {"NIFTY", "BANKNIFTY", "FINNIFTY"};
        for (String underlying : underlyings) {
            int openLots = riskService.getOpenLotsForUnderlying(underlying);
            if (openLots > 0) {
                riskService.autoHedgeOrUnwind(underlying, openLots);
            }
        }

        recordRiskEvent("FORCE_KILL_ALL", reason, 0.0, true);
    }
}
