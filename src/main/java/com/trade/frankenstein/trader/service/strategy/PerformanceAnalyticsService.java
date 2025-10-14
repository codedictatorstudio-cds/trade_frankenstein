package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.dto.PerformanceTrackingEvent;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.service.trade.TradesService;
import com.trade.frankenstein.trader.service.advice.AdviceService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PerformanceAnalyticsService {

    @Autowired
    private AdviceService adviceService;

    @Autowired
    private TradesService tradesService;

    // In-memory storage for performance tracking
    private final Map<String, PerformanceTrackingEvent> trackedAdvices = new ConcurrentHashMap<>();
    private final Map<String, List<PerformanceMetric>> performanceHistory = new ConcurrentHashMap<>();

    public void trackAdvice(PerformanceTrackingEvent event) {
        try {
            // Store the tracking event
            trackedAdvices.put(event.getAdviceId(), event);

            log.info("Tracking advice performance: {} with ML score: {}, RL confidence: {}",
                    event.getAdviceId(), event.getMlScore(), event.getRlConfidence());

            // Schedule performance evaluation (in real implementation, this would be event-driven)
            schedulePerformanceEvaluation(event);

        } catch (Exception e) {
            log.error("Failed to track advice performance: {}", e.getMessage());
        }
    }

    public PerformanceMetric evaluateAdvicePerformance(String adviceId) {
        try {
            PerformanceTrackingEvent trackingEvent = trackedAdvices.get(adviceId);
            if (trackingEvent == null) {
                log.warn("No tracking event found for advice: {}", adviceId);
                return null;
            }

            // Get the original advice
            Result<List<Advice>> adviceResult = adviceService.list();
            if (adviceResult == null || !adviceResult.isOk()) {
                return null;
            }

            Advice advice = adviceResult.get().stream()
                    .filter(a -> adviceId.equals(a.getId()))
                    .findFirst()
                    .orElse(null);

            if (advice == null) {
                log.warn("Advice not found: {}", adviceId);
                return null;
            }

            // Calculate performance metrics
            PerformanceMetric metric = new PerformanceMetric();
            metric.setAdviceId(adviceId);
            metric.setInstrumentKey(advice.getInstrument_token());
            metric.setCreatedAt(advice.getCreatedAt());
            metric.setEvaluatedAt(Instant.now());

            // Get actual trade outcome
            TradeOutcome outcome = getTradeOutcome(advice);
            metric.setOutcome(outcome.getStatus());
            metric.setActualReturn(outcome.getReturn());

            // Compare with predictions
            if (trackingEvent.getRlExpectedReward() != null) {
                BigDecimal predictionError = outcome.getReturn().subtract(trackingEvent.getRlExpectedReward());
                metric.setPredictionError(predictionError);
            }

            // Calculate accuracy metrics
            metric.setMlAccuracy(calculateMLAccuracy(trackingEvent, outcome));
            metric.setRlAccuracy(calculateRLAccuracy(trackingEvent, outcome));

            // Performance attribution
            Map<String, BigDecimal> attribution = calculatePerformanceAttribution(trackingEvent, outcome);
            metric.setPerformanceAttribution(attribution);

            // Store in history
            storePerformanceMetric(metric);

            return metric;

        } catch (Exception e) {
            log.error("Failed to evaluate advice performance for {}: {}", adviceId, e.getMessage());
            return null;
        }
    }

    public PerformanceReport generatePerformanceReport(LocalDate fromDate, LocalDate toDate) {
        try {
            PerformanceReport report = new PerformanceReport();
            report.setFromDate(fromDate);
            report.setToDate(toDate);
            report.setGeneratedAt(Instant.now());

            // Get all performance metrics in the date range
            List<PerformanceMetric> metrics = getAllMetricsInRange(fromDate, toDate);

            if (metrics.isEmpty()) {
                log.warn("No performance metrics found for date range: {} to {}", fromDate, toDate);
                return report;
            }

            // Calculate overall statistics
            OverallStats stats = calculateOverallStats(metrics);
            report.setOverallStats(stats);

            // ML model performance
            MLModelPerformance mlPerformance = calculateMLModelPerformance(metrics);
            report.setMlModelPerformance(mlPerformance);

            // RL agent performance
            RLAgentPerformance rlPerformance = calculateRLAgentPerformance(metrics);
            report.setRlAgentPerformance(rlPerformance);

            // Strategy effectiveness
            StrategyEffectiveness effectiveness = calculateStrategyEffectiveness(metrics);
            report.setStrategyEffectiveness(effectiveness);

            // Recommendations for improvement
            List<String> recommendations = generateRecommendations(metrics);
            report.setRecommendations(recommendations);

            return report;

        } catch (Exception e) {
            log.error("Failed to generate performance report: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getRealTimeMetrics() {
        try {
            Map<String, Object> metrics = new HashMap<>();

            // Get recent performance data (last 24 hours)
            Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
            List<PerformanceMetric> recentMetrics = performanceHistory.values().stream()
                    .flatMap(List::stream)
                    .filter(m -> m.getEvaluatedAt().isAfter(since))
                    .toList();

            if (!recentMetrics.isEmpty()) {
                // Win rate
                long wins = recentMetrics.stream()
                        .mapToLong(m -> m.getActualReturn().compareTo(BigDecimal.ZERO) > 0 ? 1 : 0)
                        .sum();
                double winRate = (double) wins / recentMetrics.size() * 100.0;
                metrics.put("win_rate_24h", winRate);

                // Average return
                double avgReturn = recentMetrics.stream()
                        .mapToDouble(m -> m.getActualReturn().doubleValue())
                        .average()
                        .orElse(0.0);
                metrics.put("avg_return_24h", avgReturn);

                // ML accuracy
                double mlAccuracy = recentMetrics.stream()
                        .filter(m -> m.getMlAccuracy() != null)
                        .mapToDouble(m -> m.getMlAccuracy().doubleValue())
                        .average()
                        .orElse(0.0);
                metrics.put("ml_accuracy_24h", mlAccuracy);

                // RL accuracy
                double rlAccuracy = recentMetrics.stream()
                        .filter(m -> m.getRlAccuracy() != null)
                        .mapToDouble(m -> m.getRlAccuracy().doubleValue())
                        .average()
                        .orElse(0.0);
                metrics.put("rl_accuracy_24h", rlAccuracy);

                // Total trades
                metrics.put("total_trades_24h", recentMetrics.size());

                // Active tracking
                metrics.put("active_tracking_count", trackedAdvices.size());
            } else {
                // No recent data
                metrics.put("win_rate_24h", 0.0);
                metrics.put("avg_return_24h", 0.0);
                metrics.put("ml_accuracy_24h", 0.0);
                metrics.put("rl_accuracy_24h", 0.0);
                metrics.put("total_trades_24h", 0);
                metrics.put("active_tracking_count", trackedAdvices.size());
            }

            metrics.put("last_updated", Instant.now());

            return metrics;

        } catch (Exception e) {
            log.error("Failed to get real-time metrics: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void schedulePerformanceEvaluation(PerformanceTrackingEvent event) {
        // In a real implementation, this would schedule evaluation based on market close
        // or specific time intervals. For now, we'll simulate immediate evaluation
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                evaluateAdvicePerformance(event.getAdviceId());
            }
        }, 60000); // Evaluate after 1 minute (simulate trade completion)
    }

    private TradeOutcome getTradeOutcome(Advice advice) {
        TradeOutcome outcome = new TradeOutcome();

        try {
            // Try to get actual trade data
            // This is a simulation - in reality, would query trade execution system
            Random random = new Random();

            // Simulate trade outcome
            boolean isWin = random.nextDouble() > 0.4; // 60% win rate simulation
            double returnPct = isWin ?
                    (0.05 + random.nextDouble() * 0.25) :  // Win: 5-30%
                    (-0.05 - random.nextDouble() * 0.20);  // Loss: -5 to -25%

            outcome.setStatus(isWin ? "WIN" : "LOSS");
            outcome.setReturn(bd(String.valueOf(returnPct)));
            outcome.setExecutionTime(advice.getCreatedAt().plus(Duration.ofMinutes(15 + random.nextInt(30))));

        } catch (Exception e) {
            log.error("Failed to get trade outcome for advice {}: {}", advice.getId(), e.getMessage());
            outcome.setStatus("UNKNOWN");
            outcome.setReturn(bd("0.0"));
            outcome.setExecutionTime(Instant.now());
        }

        return outcome;
    }

    private BigDecimal calculateMLAccuracy(PerformanceTrackingEvent tracking, TradeOutcome outcome) {
        try {
            if (tracking.getMlScore() == null) return null;

            // Simple accuracy calculation based on whether ML score aligned with outcome
            boolean outcomePositive = outcome.getReturn().compareTo(BigDecimal.ZERO) > 0;
            boolean mlPredictionPositive = tracking.getMlScore() > 50; // Assuming score > 50 means positive prediction

            return bd(outcomePositive == mlPredictionPositive ? "1.0" : "0.0");

        } catch (Exception e) {
            log.error("Failed to calculate ML accuracy: {}", e.getMessage());
            return bd("0.0");
        }
    }

    private BigDecimal calculateRLAccuracy(PerformanceTrackingEvent tracking, TradeOutcome outcome) {
        try {
            if (tracking.getRlExpectedReward() == null) return null;

            // Calculate accuracy based on how close the RL prediction was
            BigDecimal actualReturn = outcome.getReturn();
            BigDecimal expectedReturn = tracking.getRlExpectedReward();

            BigDecimal error = actualReturn.subtract(expectedReturn).abs();
            BigDecimal accuracy = BigDecimal.ONE.subtract(error.divide(bd("0.5"), 4, RoundingMode.HALF_UP));

            return accuracy.max(bd("0.0")).min(bd("1.0"));

        } catch (Exception e) {
            log.error("Failed to calculate RL accuracy: {}", e.getMessage());
            return bd("0.0");
        }
    }

    private Map<String, BigDecimal> calculatePerformanceAttribution(PerformanceTrackingEvent tracking,
                                                                    TradeOutcome outcome) {
        Map<String, BigDecimal> attribution = new HashMap<>();

        try {
            BigDecimal totalReturn = outcome.getReturn();

            // Attribute performance to different components
            // This is simplified - in reality would be more sophisticated

            if (tracking.getMlScore() != null) {
                // Attribute based on ML score contribution
                double mlContribution = (tracking.getMlScore().doubleValue() - 50.0) / 100.0; // Normalize
                attribution.put("ML_CONTRIBUTION", totalReturn.multiply(bd(String.valueOf(mlContribution * 0.4))));
            }

            if (tracking.getRlConfidence() != null) {
                // Attribute based on RL confidence
                double rlContribution = tracking.getRlConfidence().doubleValue();
                attribution.put("RL_CONTRIBUTION", totalReturn.multiply(bd(String.valueOf(rlContribution * 0.3))));
            }

            // Traditional analysis contribution (remainder)
            BigDecimal mlContrib = attribution.getOrDefault("ML_CONTRIBUTION", bd("0.0"));
            BigDecimal rlContrib = attribution.getOrDefault("RL_CONTRIBUTION", bd("0.0"));
            attribution.put("TRADITIONAL_CONTRIBUTION", totalReturn.subtract(mlContrib).subtract(rlContrib));

        } catch (Exception e) {
            log.error("Failed to calculate performance attribution: {}", e.getMessage());
        }

        return attribution;
    }

    private void storePerformanceMetric(PerformanceMetric metric) {
        String key = metric.getInstrumentKey();
        performanceHistory.computeIfAbsent(key, k -> new ArrayList<>()).add(metric);

        // Keep only last 1000 metrics per instrument to avoid memory issues
        List<PerformanceMetric> metrics = performanceHistory.get(key);
        if (metrics.size() > 1000) {
            metrics.subList(0, metrics.size() - 1000).clear();
        }
    }

    private List<PerformanceMetric> getAllMetricsInRange(LocalDate fromDate, LocalDate toDate) {
        Instant fromInstant = fromDate.atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant();

        return performanceHistory.values().stream()
                .flatMap(List::stream)
                .filter(m -> m.getCreatedAt().isAfter(fromInstant) && m.getCreatedAt().isBefore(toInstant))
                .collect(Collectors.toList());
    }

    private OverallStats calculateOverallStats(List<PerformanceMetric> metrics) {
        OverallStats stats = new OverallStats();

        if (metrics.isEmpty()) return stats;

        // Win rate
        long wins = metrics.stream()
                .mapToLong(m -> m.getActualReturn().compareTo(BigDecimal.ZERO) > 0 ? 1 : 0)
                .sum();
        stats.setWinRate(bd(String.valueOf((double) wins / metrics.size())));

        // Average return
        double avgReturn = metrics.stream()
                .mapToDouble(m -> m.getActualReturn().doubleValue())
                .average()
                .orElse(0.0);
        stats.setAverageReturn(bd(String.valueOf(avgReturn)));

        // Total trades
        stats.setTotalTrades(metrics.size());

        // Best and worst trades
        Optional<PerformanceMetric> bestTrade = metrics.stream()
                .max(Comparator.comparing(PerformanceMetric::getActualReturn));
        bestTrade.ifPresent(m -> stats.setBestReturn(m.getActualReturn()));

        Optional<PerformanceMetric> worstTrade = metrics.stream()
                .min(Comparator.comparing(PerformanceMetric::getActualReturn));
        worstTrade.ifPresent(m -> stats.setWorstReturn(m.getActualReturn()));

        return stats;
    }

    private MLModelPerformance calculateMLModelPerformance(List<PerformanceMetric> metrics) {
        MLModelPerformance performance = new MLModelPerformance();

        List<PerformanceMetric> mlMetrics = metrics.stream()
                .filter(m -> m.getMlAccuracy() != null)
                .toList();

        if (!mlMetrics.isEmpty()) {
            double avgAccuracy = mlMetrics.stream()
                    .mapToDouble(m -> m.getMlAccuracy().doubleValue())
                    .average()
                    .orElse(0.0);
            performance.setAverageAccuracy(bd(String.valueOf(avgAccuracy)));

            // Model reliability
            long highAccuracy = mlMetrics.stream()
                    .mapToLong(m -> m.getMlAccuracy().compareTo(bd("0.7")) > 0 ? 1 : 0)
                    .sum();
            performance.setReliabilityScore(bd(String.valueOf((double) highAccuracy / mlMetrics.size())));
        }

        return performance;
    }

    private RLAgentPerformance calculateRLAgentPerformance(List<PerformanceMetric> metrics) {
        RLAgentPerformance performance = new RLAgentPerformance();

        List<PerformanceMetric> rlMetrics = metrics.stream()
                .filter(m -> m.getRlAccuracy() != null)
                .toList();

        if (!rlMetrics.isEmpty()) {
            double avgAccuracy = rlMetrics.stream()
                    .mapToDouble(m -> m.getRlAccuracy().doubleValue())
                    .average()
                    .orElse(0.0);
            performance.setAverageAccuracy(bd(String.valueOf(avgAccuracy)));

            // Learning progress (simplified)
            performance.setLearningProgress(bd("0.75")); // Placeholder
        }

        return performance;
    }

    private StrategyEffectiveness calculateStrategyEffectiveness(List<PerformanceMetric> metrics) {
        StrategyEffectiveness effectiveness = new StrategyEffectiveness();

        if (!metrics.isEmpty()) {
            // Risk-adjusted returns (simplified Sharpe ratio approximation)
            double avgReturn = metrics.stream()
                    .mapToDouble(m -> m.getActualReturn().doubleValue())
                    .average()
                    .orElse(0.0);

            double stdDev = Math.sqrt(metrics.stream()
                    .mapToDouble(m -> Math.pow(m.getActualReturn().doubleValue() - avgReturn, 2))
                    .average()
                    .orElse(0.01));

            double sharpeRatio = stdDev > 0 ? avgReturn / stdDev : 0.0;
            effectiveness.setSharpeRatio(bd(String.valueOf(sharpeRatio)));

            // Maximum drawdown (simplified)
            double maxDrawdown = metrics.stream()
                    .mapToDouble(m -> m.getActualReturn().doubleValue())
                    .min()
                    .orElse(0.0);
            effectiveness.setMaxDrawdown(bd(String.valueOf(maxDrawdown)));
        }

        return effectiveness;
    }

    private List<String> generateRecommendations(List<PerformanceMetric> metrics) {
        List<String> recommendations = new ArrayList<>();

        if (metrics.isEmpty()) {
            recommendations.add("Insufficient data for recommendations");
            return recommendations;
        }

        // Analyze performance patterns and suggest improvements
        double avgReturn = metrics.stream()
                .mapToDouble(m -> m.getActualReturn().doubleValue())
                .average()
                .orElse(0.0);

        if (avgReturn < 0.05) {
            recommendations.add("Consider increasing risk tolerance or improving entry criteria");
        }

        double winRate = metrics.stream()
                .mapToDouble(m -> m.getActualReturn().compareTo(BigDecimal.ZERO) > 0 ? 1.0 : 0.0)
                .average()
                .orElse(0.0);

        if (winRate < 0.6) {
            recommendations.add("Focus on improving trade selection accuracy");
        }

        // ML-specific recommendations
        List<PerformanceMetric> mlMetrics = metrics.stream()
                .filter(m -> m.getMlAccuracy() != null)
                .collect(Collectors.toList());

        if (!mlMetrics.isEmpty()) {
            double mlAccuracy = mlMetrics.stream()
                    .mapToDouble(m -> m.getMlAccuracy().doubleValue())
                    .average()
                    .orElse(0.0);

            if (mlAccuracy < 0.7) {
                recommendations.add("Consider retraining ML models with more recent data");
            }
        }

        return recommendations;
    }


    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // Inner classes for structured data
    public static class TradeOutcome {
        // Getters and setters
        @Setter
        @Getter
        private String status;
        private BigDecimal return_;
        @Getter
        @Setter
        private Instant executionTime;

        public BigDecimal getReturn() {
            return return_;
        }

        public void setReturn(BigDecimal return_) {
            this.return_ = return_;
        }
    }

    @Setter
    @Getter
    public static class PerformanceMetric {
        // Getters and setters
        private String adviceId;
        private String instrumentKey;
        private Instant createdAt;
        private Instant evaluatedAt;
        private String outcome;
        private BigDecimal actualReturn;
        private BigDecimal predictionError;
        private BigDecimal mlAccuracy;
        private BigDecimal rlAccuracy;
        private Map<String, BigDecimal> performanceAttribution;

    }

    // Additional inner classes for reports
    @Setter
    @Getter
    public static class PerformanceReport {
        // Getters and setters
        private LocalDate fromDate;
        private LocalDate toDate;
        private Instant generatedAt;
        private OverallStats overallStats;
        private MLModelPerformance mlModelPerformance;
        private RLAgentPerformance rlAgentPerformance;
        private StrategyEffectiveness strategyEffectiveness;
        private List<String> recommendations;

    }

    @Setter
    @Getter
    public static class OverallStats {
        // Getters and setters
        private BigDecimal winRate;
        private BigDecimal averageReturn;
        private Integer totalTrades;
        private BigDecimal bestReturn;
        private BigDecimal worstReturn;

    }

    @Setter
    @Getter
    public static class MLModelPerformance {
        // Getters and setters
        private BigDecimal averageAccuracy;
        private BigDecimal reliabilityScore;

    }

    @Setter
    @Getter
    public static class RLAgentPerformance {
        // Getters and setters
        private BigDecimal averageAccuracy;
        private BigDecimal learningProgress;

    }

    @Setter
    @Getter
    public static class StrategyEffectiveness {
        // Getters and setters
        private BigDecimal sharpeRatio;
        private BigDecimal maxDrawdown;

    }
}
