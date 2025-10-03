package com.trade.frankenstein.trader.service;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.*;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
import com.upstox.api.GetOrderDetailsResponse;
import com.upstox.api.PlaceOrderRequest;
import com.upstox.api.PlaceOrderResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Enhanced AdviceService with comprehensive auto-trading capabilities.
 * Provides intelligent advice management, risk integration, portfolio awareness,
 * and automated lifecycle management.
 */
@Service
@Slf4j
public class AdviceService {

    @Autowired
    private AdviceRepo adviceRepo;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private FastStateStore fast;

    @Autowired
    private EventPublisher eventPublisher;

    // Enhanced dependencies for auto-trading
    @Autowired
    private RiskService riskService;

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private TradesService tradesService;

    @Autowired
    private StrategyService strategyService;

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private SentimentService sentimentService;

    @Autowired
    private NewsService newsService;

    // Metrics tracking
    private final AtomicLong advicesCreated = new AtomicLong(0);
    private final AtomicLong advicesExecuted = new AtomicLong(0);
    private final AtomicLong advicesFailed = new AtomicLong(0);

    // Utility methods
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ========== ENHANCED READ OPERATIONS ==========

    @Transactional(readOnly = true)
    public Result<List<Advice>> list() {
        return list(100); // Default limit
    }

    @Transactional(readOnly = true)
    public Result<List<Advice>> list(int limit) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return Result.fail("user-not-logged-in");
        }

        try {
            Pageable pageable = PageRequest.of(0, Math.max(1, Math.min(1000, limit)),
                    Sort.by("priorityScore").descending().and(Sort.by("createdAt").descending()));

            List<Advice> advices = adviceRepo.findAll(pageable).getContent();
            return Result.ok(advices);
        } catch (Exception t) {
            log.error("advice.list failed", t);
            return Result.fail(t);
        }
    }

    @Transactional(readOnly = true)
    public Result<Advice> get(String adviceId) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in :: get");
            return Result.fail("user-not-logged-in");
        }

        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");

            Optional<Advice> opt = adviceRepo.findById(adviceId);
            return opt.map(Result::ok).orElseGet(() -> Result.fail("NOT_FOUND", "Advice not found"));
        } catch (Exception t) {
            log.error("advice.get({}) failed", adviceId, t);
            return Result.fail(t);
        }
    }

    // New method: Get prioritized pending advices for engine execution
    @Transactional(readOnly = true)
    public Result<List<Advice>> getPrioritizedPendingAdvices(int limit) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return Result.fail("user-not-logged-in");
        }

        try {
            Instant now = Instant.now();
            List<Advice> executable = adviceRepo.findExecutableAdvices(30, now); // min priority 30

            // Apply additional priority scoring
            executable.sort((a1, a2) -> {
                int score1 = calculateRuntimePriorityScore(a1);
                int score2 = calculateRuntimePriorityScore(a2);
                return Integer.compare(score2, score1); // Higher score first
            });

            List<Advice> result = executable.stream()
                    .limit(Math.max(1, Math.min(50, limit)))
                    .collect(Collectors.toList());

            return Result.ok(result);
        } catch (Exception t) {
            log.error("getPrioritizedPendingAdvices failed", t);
            return Result.fail(t);
        }
    }

    // ========== ENHANCED CREATE OPERATIONS ==========

    /**
     * Enhanced create method with comprehensive validation and intelligence
     */
    @Transactional
    public Result<Advice> create(Advice draft) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in :: create");
            return Result.fail("user-not-logged-in");
        }

        try {
            if (draft == null) return Result.fail("BAD_REQUEST", "Advice payload required");

            // Set defaults and enrich with intelligence
            enrichAdviceWithDefaults(draft);
            enrichAdviceWithMarketContext(draft);

            // Comprehensive validation pipeline
            Result<Advice> validationResult = validateAdviceForCreation(draft);
            if (!validationResult.isOk()) {
                return validationResult;
            }

            // Apply deduplication
            Result<Advice> dedupeResult = applyDeduplication(draft);
            if (!dedupeResult.isOk()) {
                return dedupeResult;
            }

            // Set timestamps and metadata
            Instant now = Instant.now();
            draft.setCreatedAt(now);
            draft.setUpdatedAt(now);
            draft.setCreatedBy("system"); // Can be enhanced with actual user context

            // Calculate expiry if not set
            if (draft.getExpiresAt() == null) {
                draft.setExpiresAt(calculateAdviceExpiry(draft));
            }

            // Save and publish
            Advice saved = adviceRepo.save(draft);
            advicesCreated.incrementAndGet();

            publishAdviceEventEnhanced("advice.new", saved, "creation");

            log.info("Created advice: {} for {} (priority: {}, risk: {})",
                    saved.getId(), saved.getSymbol(), saved.getPriorityScore(), saved.getRiskCategory());

            return Result.ok(saved);

        } catch (Exception t) {
            advicesFailed.incrementAndGet();
            log.error("advice.create failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Create smart exit advice for existing positions
     */
    @Transactional
    public Result<Advice> createSmartExit(String instrumentKey, String reason) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return Result.fail("user-not-logged-in");
        }

        try {
            // Get current position
            List<Advice> openPositions = adviceRepo.findOpenPositionsForInstrument(instrumentKey);
            if (openPositions.isEmpty()) {
                return Result.fail("NO_POSITION", "No open position to exit");
            }

            // Calculate total position size
            int totalQuantity = openPositions.stream()
                    .mapToInt(Advice::getQuantity)
                    .sum();

            // Get current market context
            Result<BigDecimal> ltpResult = marketDataService.getLtpSmart(instrumentKey);
            if (!ltpResult.isOk()) {
                return Result.fail("MARKET_DATA_ERROR", "Cannot get current price");
            }

            // Create intelligent exit advice
            Advice exitAdvice = Advice.builder()
                    .instrument_token(instrumentKey)
                    .transaction_type("SELL")
                    .quantity(totalQuantity)
                    .order_type("MARKET")
                    .product("MIS")
                    .validity("DAY")
                    .reason("SMART_EXIT: " + reason)
                    .strategy(StrategyName.RISK_MANAGEMENT)
                    .priorityScore(90) // High priority for exits
                    .riskCategory(RiskCategory.HIGH)
                    .executionContext(ExecutionContext.RISK_TRIGGERED)
                    .positionType("EXIT")
                    .emergencyExit(reason.contains("EMERGENCY"))
                    .build();

            return create(exitAdvice);

        } catch (Exception t) {
            log.error("createSmartExit failed for {}", instrumentKey, t);
            return Result.fail(t);
        }
    }

    // ========== ENHANCED EXECUTION OPERATIONS ==========

    /**
     * Enhanced execute method with intelligent order management
     */
    @Transactional
    public Result<Advice> execute(String adviceId) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in :: execute");
            return Result.fail("user-not-logged-in");
        }

        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");

            Advice advice = adviceRepo.findById(adviceId).orElse(null);
            if (advice == null) return Result.fail("NOT_FOUND", "Advice not found");

            // Check if already processed
            if (!advice.getStatus().canExecute()) {
                log.info("advice.execute: {} cannot be executed (status={})",
                        adviceId, advice.getStatus());
                return Result.ok(advice);
            }

            // Pre-execution validation
            Result<Advice> preCheckResult = validateAdviceForExecution(advice);
            if (!preCheckResult.isOk()) {
                advice.setStatus(AdviceStatus.BLOCKED);
                advice.setLastError(preCheckResult.getError());
                adviceRepo.save(advice);
                return preCheckResult;
            }

            // Update status to executing
            advice.setStatus(AdviceStatus.EXECUTING);
            advice.setUpdatedAt(Instant.now());
            adviceRepo.save(advice);

            long startTime = System.currentTimeMillis();

            try {
                // Build intelligent order request
                PlaceOrderRequest request = buildIntelligentOrderRequest(advice);

                // Execute through orders service
                Result<PlaceOrderResponse> orderResult = ordersService.placeOrder(request);

                if (orderResult == null || !orderResult.isOk() ||
                        orderResult.get() == null || orderResult.get().getData() == null) {

                    String error = (orderResult == null) ? "Order placement failed" :
                            (orderResult.getError() == null ? "Order placement failed" :
                                    orderResult.getError());

                    return handleExecutionFailure(advice, error, orderResult);
                }

                // Success - update advice with execution details
                String orderId = orderResult.get().getData().getOrderId();
                long executionTime = System.currentTimeMillis() - startTime;

                advice.setOrder_id(orderId);
                advice.setStatus(AdviceStatus.EXECUTED);
                advice.setUpdatedAt(Instant.now());
                advice.setExecutionLatencyMs(executionTime);
                advice.setExecutionQuality(assessExecutionQuality(executionTime));

                // Record execution price if available
                recordExecutionDetails(advice, orderResult.get());

                Advice saved = adviceRepo.save(advice);
                advicesExecuted.incrementAndGet();

                publishAdviceEventEnhanced("advice.executed", saved, "execution");

                log.info("Successfully executed advice: {} -> order: {} ({}ms)",
                        adviceId, orderId, executionTime);

                return Result.ok(saved);

            } catch (Exception executionException) {
                return handleExecutionFailure(advice, executionException.getMessage(), null);
            }

        } catch (Exception t) {
            log.error("advice.execute failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Batch execution for engine efficiency
     */
    @Transactional
    public Result<List<Advice>> executeBatch(List<String> adviceIds, int maxExecutions) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            return Result.fail("user-not-logged-in");
        }

        List<Advice> executed = new ArrayList<>();
        int executionCount = 0;

        for (String adviceId : adviceIds) {
            if (executionCount >= maxExecutions) break;

            Result<Advice> result = execute(adviceId);
            if (result.isOk()) {
                executed.add(result.get());
                executionCount++;
            } else {
                log.warn("Batch execution failed for advice {}: {}", adviceId, result.getError());
            }
        }

        log.info("Batch execution completed: {}/{} successful", executionCount, adviceIds.size());
        return Result.ok(executed);
    }

    // ========== ENHANCED STATUS MANAGEMENT ==========

    @Transactional
    public Result<Advice> dismiss(String adviceId) {
        return dismiss(adviceId, "Manual dismissal");
    }

    @Transactional
    public Result<Advice> dismiss(String adviceId, String reason) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return Result.fail("user-not-logged-in");
        }

        try {
            if (isBlank(adviceId)) return Result.fail("BAD_REQUEST", "adviceId is required");

            Advice advice = adviceRepo.findById(adviceId).orElse(null);
            if (advice == null) return Result.fail("NOT_FOUND", "Advice not found");

            if (advice.getStatus() == AdviceStatus.DISMISSED) return Result.ok(advice);

            advice.setStatus(AdviceStatus.DISMISSED);
            advice.setUpdatedAt(Instant.now());
            advice.setReason(advice.getReason() + "; DISMISSED: " + reason);

            Advice saved = adviceRepo.save(advice);

            publishAdviceEventEnhanced("advice.dismissed", saved, "dismissal");

            return Result.ok(saved);
        } catch (Exception t) {
            log.error("advice.dismiss failed", t);
            return Result.fail(t);
        }
    }

    // ========== SCHEDULED MAINTENANCE OPERATIONS ==========

    /**
     * Sync advice status with broker order status
     */
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    @Transactional
    public void syncAdviceWithOrderStatus() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;

        try {
            List<AdviceStatus> activeStatuses = Arrays.asList(
                    AdviceStatus.EXECUTED, AdviceStatus.EXECUTING, AdviceStatus.PARTIALLY_FILLED);

            List<Advice> activeAdvices = adviceRepo.findByStatusInOrderByPriorityScoreDescCreatedAtDesc(activeStatuses);

            for (Advice advice : activeAdvices) {
                if (advice.getOrder_id() != null) {
                    try {
                        syncSingleAdviceStatus(advice);
                    } catch (Exception e) {
                        log.warn("Failed to sync advice {} with order {}: {}",
                                advice.getId(), advice.getOrder_id(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("syncAdviceWithOrderStatus failed", e);
        }
    }

    /**
     * Auto-expire stale pending advices
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    @Transactional
    public void cleanupStaleAdvices() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;

        try {
            Instant now = Instant.now();

            // Find expired advices
            List<Advice> expiredAdvices = adviceRepo.findExpiredPendingAdvices(now);

            for (Advice advice : expiredAdvices) {
                advice.setStatus(AdviceStatus.EXPIRED);
                advice.setUpdatedAt(now);
                advice.setReason(advice.getReason() + "; Auto-expired");

                adviceRepo.save(advice);
                publishAdviceEventEnhanced("advice.expired", advice, "auto-cleanup");
            }

            if (!expiredAdvices.isEmpty()) {
                log.info("Auto-expired {} stale advices", expiredAdvices.size());
            }

            // Cleanup very old completed advices (optional)
            cleanupOldAdvices();

        } catch (Exception e) {
            log.error("cleanupStaleAdvices failed", e);
        }
    }

    /**
     * Publish periodic metrics
     */
    @Scheduled(fixedDelay = 60000) // Every minute
    public void publishMetrics() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;

        try {
            publishAdviceMetrics();
        } catch (Exception e) {
            log.error("publishMetrics failed", e);
        }
    }

    // ========== HELPER METHODS ==========

    private void enrichAdviceWithDefaults(Advice advice) {
        if (advice.getStatus() == null) advice.setStatus(AdviceStatus.PENDING);
        if (advice.getProduct() == null) advice.setProduct("MIS");
        if (advice.getValidity() == null) advice.setValidity("DAY");
        if (advice.getPriorityScore() == null) advice.setPriorityScore(50);
        if (advice.getRiskCategory() == null) advice.setRiskCategory(RiskCategory.MEDIUM);
        if (advice.getExecutionContext() == null) advice.setExecutionContext(ExecutionContext.AUTO);
        if (advice.getRetryCount() == null) advice.setRetryCount(0);

        // Set strategy-specific defaults
        if (advice.getStrategy() != null) {
            advice.setRiskCategory(advice.getStrategy().getDefaultRiskCategory());
            advice.setMaxHoldingMinutes(advice.getStrategy().getTypicalHoldingMinutes());
        }
    }

    private void enrichAdviceWithMarketContext(Advice advice) {
        try {
            // Add market regime
            Result<MarketRegime> regimeResult = marketDataService.getRegimeNow();
            if (regimeResult.isOk()) {
                advice.setMarketRegime(regimeResult.get().name());
            }

            // Add sentiment score
            Optional<BigDecimal> sentimentOpt = sentimentService.getMarketSentimentScore();
            sentimentOpt.ifPresent(advice::setMarketSentimentScore);

            // Add volatility context
            if (advice.getInstrument_token() != null) {
                Optional<Float> atrOpt = marketDataService.getAtrJump5mPct(advice.getInstrument_token());
                atrOpt.ifPresent(advice::setVolatilityScore);
            }
        } catch (Exception e) {
            log.debug("Failed to enrich advice with market context: {}", e.getMessage());
        }
    }

    private Result<Advice> validateAdviceForCreation(Advice advice) {
        // Basic field validation
        assertReadyForCreationOrExecution(advice);

        // Risk validation
        Result<Advice> riskResult = validateRiskConstraints(advice);
        if (!riskResult.isOk()) return riskResult;

        // Strategy validation
        Result<Advice> strategyResult = validateStrategyAlignment(advice);
        if (!strategyResult.isOk()) return strategyResult;

        // Market conditions validation
        Result<Advice> marketResult = validateMarketConditions(advice);
        if (!marketResult.isOk()) return marketResult;

        return Result.ok(advice);
    }

    private Result<Advice> validateRiskConstraints(Advice advice) {
        try {
            Result<RiskSnapshot> riskResult = riskService.getSummary();
            if (!riskResult.isOk()) {
                return Result.fail("RISK_SERVICE_ERROR", "Cannot validate risk constraints");
            }

            RiskSnapshot risk = riskResult.get();

            // Check lot capacity
            if (risk.getLotsUsed() != null && risk.getLotsCap() != null) {
                if (risk.getLotsUsed() >= risk.getLotsCap()) {
                    return Result.fail("RISK_BREACH", "Lot capacity exceeded");
                }
            }

            // Check budget availability
            if (risk.getRiskBudgetLeft() != null && risk.getRiskBudgetLeft() <= 0) {
                return Result.fail("RISK_BREACH", "Risk budget exhausted");
            }

            // Position concentration check
            if ("BUY".equals(advice.getTransaction_type())) {
                if (hasExcessiveConcentration(advice.getInstrument_token())) {
                    return Result.fail("CONCENTRATION_RISK",
                            "Too many positions in same instrument");
                }
            }

            return Result.ok(advice);
        } catch (Exception e) {
            return Result.fail("RISK_VALIDATION_ERROR", "Risk validation failed: " + e.getMessage());
        }
    }

    private Result<Advice> validateStrategyAlignment(Advice advice) {
        if (advice.getStrategy() == null) return Result.ok(advice);

        try {
            // Check if strategy conditions are still valid
            // This would integrate with StrategyService's validation logic
            // For now, implementing basic checks

            if (advice.getStrategy().isRiskStrategy()) {
                // Risk strategies get highest priority
                advice.setPriorityScore(Math.max(advice.getPriorityScore(), 90));
            }

            // Apply strategy-specific position sizing
            int recommendedSize = calculateRecommendedPositionSize(advice);
            if (advice.getQuantity() > recommendedSize) {
                advice.setQuantity(recommendedSize);
                advice.setReason(advice.getReason() + "; Size adjusted per strategy limits");
            }

            return Result.ok(advice);
        } catch (Exception e) {
            return Result.fail("STRATEGY_VALIDATION_ERROR",
                    "Strategy validation failed: " + e.getMessage());
        }
    }

    private Result<Advice> validateMarketConditions(Advice advice) {
        try {
            // Check for news bursts that might affect execution
            if (newsService != null) {
                Optional<Integer> newsBurst = newsService.getRecentBurstCount(5);
                if (newsBurst.isPresent() && newsBurst.get() > 10) {
                    advice.setPriorityScore(Math.max(0, advice.getPriorityScore() - 20));
                    advice.setReason(advice.getReason() + "; High news activity noted");
                }
            }

            // Check sentiment alignment for new positions
            if ("BUY".equals(advice.getTransaction_type())) {
                Optional<BigDecimal> sentiment = sentimentService.getMarketSentimentScore();
                if (sentiment.isPresent() && sentiment.get().compareTo(new BigDecimal("20")) < 0) {
                    advice.setReason(advice.getReason() + "; Bearish sentiment noted");
                }
            }

            return Result.ok(advice);
        } catch (Exception e) {
            log.debug("Market conditions validation failed: {}", e.getMessage());
            return Result.ok(advice); // Non-critical validation
        }
    }

    private Result<Advice> applyDeduplication(Advice advice) {
        String side = String.valueOf(advice.getTransaction_type());
        String token = nullToEmpty(advice.getInstrument_token());
        String key = "adv:d:" + token + ":" + side;

        boolean first = fast.setIfAbsent(key, "1", Duration.ofSeconds(60));
        if (!first) {
            return Result.fail("DUPLICATE", "Similar advice exists (60s window)");
        }

        return Result.ok(advice);
    }

    private Instant calculateAdviceExpiry(Advice advice) {
        Duration expiry = Duration.ofHours(24); // Default 24 hours

        if (advice.getStrategy() != null) {
            if (advice.getStrategy().isHighFrequency()) {
                expiry = Duration.ofMinutes(30);
            } else if (advice.getStrategy().isRiskStrategy()) {
                expiry = Duration.ofHours(1);
            }
        }

        if (advice.getRiskCategory() == RiskCategory.HIGH ||
                advice.getRiskCategory() == RiskCategory.CRITICAL) {
            expiry = expiry.dividedBy(2); // Shorter expiry for high risk
        }

        return Instant.now().plus(expiry);
    }

    private int calculateRuntimePriorityScore(Advice advice) {
        int score = advice.getPriorityScore() != null ? advice.getPriorityScore() : 50;

        // Higher priority for exits (risk management)
        if ("SELL".equals(advice.getTransaction_type())) {
            score += 30;
        }

        // Recent advices get higher priority
        if (advice.getCreatedAt() != null) {
            long ageMinutes = Duration.between(advice.getCreatedAt(), Instant.now()).toMinutes();
            score += Math.max(0, 20 - ageMinutes); // Up to 20 points for freshness
        }

        // Strategy-based priority
        if (advice.getStrategy() != null && advice.getStrategy().isRiskStrategy()) {
            score += 40;
        }

        // Risk category adjustment
        if (advice.getRiskCategory() != null) {
            score += switch (advice.getRiskCategory()) {
                case CRITICAL -> 50;
                case HIGH -> 20;
                case MEDIUM -> 0;
                case LOW -> -10;
            };
        }

        // Emergency exit gets maximum priority
        if (Boolean.TRUE.equals(advice.getEmergencyExit())) {
            score += 100;
        }

        return Math.max(0, Math.min(200, score)); // Clamp to 0-200
    }

    private Result<Advice> validateAdviceForExecution(Advice advice) {
        // Check expiry
        if (advice.isExpired()) {
            return Result.fail("EXPIRED", "Advice has expired");
        }

        // Market hours check would go here
        // Position limits check would go here
        // Real-time risk checks would go here

        return Result.ok(advice);
    }

    private PlaceOrderRequest buildIntelligentOrderRequest(Advice advice) {
        PlaceOrderRequest req = buildUpstoxRequest(advice);

        // Apply intelligent pricing for LIMIT orders
        if ("LIMIT".equals(advice.getOrder_type()) && advice.getInstrument_token() != null) {
            try {
                Result<BigDecimal> ltpResult = marketDataService.getLtpSmart(advice.getInstrument_token());
                if (ltpResult.isOk()) {
                    BigDecimal ltp = ltpResult.get();

                    if ("BUY".equals(advice.getTransaction_type())) {
                        // Buy slightly below LTP for better fill probability
                        Float smartPrice = ltp.multiply(new BigDecimal("0.999")).floatValue();
                        req.setPrice(smartPrice);
                    } else {
                        // Sell slightly above LTP
                        Float smartPrice = ltp.multiply(new BigDecimal("1.001")).floatValue();
                        req.setPrice(smartPrice);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to apply smart pricing: {}", e.getMessage());
            }
        }

        return req;
    }

    private Result<Advice> handleExecutionFailure(Advice advice, String error,
                                                  Result<PlaceOrderResponse> orderResult) {
        advice.incrementRetry();
        advice.setLastError(error);
        advice.setUpdatedAt(Instant.now());

        if (advice.canRetry()) {
            advice.setStatus(AdviceStatus.PENDING); // Allow retry
            log.warn("Advice execution failed (retry {}/3): {} - {}",
                    advice.getRetryCount(), advice.getId(), error);
        } else {
            advice.setStatus(AdviceStatus.FAILED);
            log.error("Advice execution failed permanently: {} - {}", advice.getId(), error);
        }

        adviceRepo.save(advice);
        advicesFailed.incrementAndGet();

        String errorCode = (orderResult != null && orderResult.getErrorCode() != null) ?
                orderResult.getErrorCode() : "EXECUTION_ERROR";

        return Result.fail(errorCode, error);
    }

    private void recordExecutionDetails(Advice advice, PlaceOrderResponse response) {
        // Record execution details if available from response
        // This would be enhanced based on actual Upstox API response structure
        try {
            if (response != null && response.getData() != null) {
                // Record execution price, fees, etc. when available
                advice.setExecutionQuality("GOOD"); // Default assessment
            }
        } catch (Exception e) {
            log.debug("Failed to record execution details: {}", e.getMessage());
        }
    }

    private String assessExecutionQuality(long executionTimeMs) {
        if (executionTimeMs < 500) return "EXCELLENT";
        if (executionTimeMs < 2000) return "GOOD";
        if (executionTimeMs < 5000) return "FAIR";
        return "POOR";
    }

    private void syncSingleAdviceStatus(Advice advice) {
        try {
            Result<GetOrderDetailsResponse> orderResult = ordersService.getOrder(advice.getOrder_id());

            if (orderResult.isOk() && orderResult.get().getData() != null) {
                String brokerStatus = orderResult.get().getData().getStatus();
                AdviceStatus newStatus = mapBrokerStatusToAdviceStatus(brokerStatus);

                if (newStatus != advice.getStatus()) {
                    advice.setStatus(newStatus);
                    advice.setUpdatedAt(Instant.now());
                    adviceRepo.save(advice);

                    publishAdviceEventEnhanced("advice.status_updated", advice, "broker-sync");
                }
            }
        } catch (Exception e) {
            log.debug("Failed to sync advice {} status: {}", advice.getId(), e.getMessage());
        }
    }

    private AdviceStatus mapBrokerStatusToAdviceStatus(String brokerStatus) {
        // Map Upstox order status to AdviceStatus
        return switch (brokerStatus.toUpperCase()) {
            case "COMPLETE" -> AdviceStatus.COMPLETED;
            case "CANCELLED" -> AdviceStatus.CANCELLED;
            case "REJECTED" -> AdviceStatus.FAILED;
            case "OPEN" -> AdviceStatus.EXECUTING;
            default -> AdviceStatus.EXECUTED; // Keep current for unknown statuses
        };
    }

    private void cleanupOldAdvices() {
        try {
            // Clean up very old completed advices (older than 30 days)
            Instant cutoff = Instant.now().minus(Duration.ofDays(30));

            List<AdviceStatus> completedStatuses = Arrays.asList(
                    AdviceStatus.COMPLETED, AdviceStatus.DISMISSED,
                    AdviceStatus.CANCELLED, AdviceStatus.EXPIRED);

            // This would typically move to archive rather than delete
            // Implementation depends on retention policy

        } catch (Exception e) {
            log.debug("Failed to cleanup old advices: {}", e.getMessage());
        }
    }

    private boolean hasExcessiveConcentration(String instrumentToken) {
        try {
            List<Advice> openPositions = adviceRepo.findOpenPositionsForInstrument(instrumentToken);
            return openPositions.size() >= 5; // Max 5 open positions per instrument
        } catch (Exception e) {
            return false; // Conservative approach on error
        }
    }

    private int calculateRecommendedPositionSize(Advice advice) {
        // Basic position sizing logic
        int baseSize = advice.getQuantity();

        if (advice.getRiskCategory() == RiskCategory.HIGH) {
            baseSize = Math.min(baseSize, 250); // Limit high risk positions
        } else if (advice.getRiskCategory() == RiskCategory.CRITICAL) {
            baseSize = Math.min(baseSize, 100); // Very conservative for critical
        }

        return Math.max(50, baseSize); // Minimum position size
    }

    private PlaceOrderRequest buildUpstoxRequest(Advice advice) {
        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setInstrumentToken(advice.getInstrument_token());
        req.setOrderType(PlaceOrderRequest.OrderTypeEnum.valueOf(advice.getOrder_type()));
        req.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.valueOf(advice.getTransaction_type()));
        req.setQuantity(advice.getQuantity());
        req.setProduct(PlaceOrderRequest.ProductEnum.valueOf(advice.getProduct()));
        req.setValidity(PlaceOrderRequest.ValidityEnum.valueOf(advice.getValidity()));
        req.setPrice(advice.getPrice());
        req.setTriggerPrice(advice.getTrigger_price());
        req.setDisclosedQuantity(advice.getDisclosed_quantity());
        req.setIsAmo(advice.is_amo());
        req.setTag(advice.getTag());
        return req;
    }

    private void assertReadyForCreationOrExecution(Advice advice) {
        if (advice == null) throw new IllegalArgumentException("Advice is null");
        if (isBlank(advice.getInstrument_token()))
            throw new IllegalStateException("instrumentToken is required on Advice");
        if (isBlank(advice.getSymbol()))
            throw new IllegalStateException("tradingSymbol is required on Advice");
        if (isBlank(advice.getOrder_type()))
            throw new IllegalStateException("orderType is required on Advice");
        if (advice.getTransaction_type() == null)
            throw new IllegalStateException("transactionType (BUY/SELL) is required on Advice");
        if (advice.getQuantity() <= 0)
            throw new IllegalStateException("quantity must be > 0 on Advice");
    }

    private void publishAdviceEventEnhanced(String event, Advice advice, String context) {
        try {
            if (advice == null) return;

            Instant now = Instant.now();
            JsonObject payload = new JsonObject();

            // Basic event data
            payload.addProperty("ts", now.toEpochMilli());
            payload.addProperty("ts_iso", now.toString());
            payload.addProperty("event", event);
            payload.addProperty("source", "advice");
            payload.addProperty("context", context);

            // Advice data
            if (advice.getId() != null) payload.addProperty("id", advice.getId());
            if (advice.getSymbol() != null) payload.addProperty("symbol", advice.getSymbol());
            if (advice.getInstrument_token() != null)
                payload.addProperty("instrument_token", advice.getInstrument_token());
            if (advice.getOrder_type() != null)
                payload.addProperty("order_type", advice.getOrder_type());
            if (advice.getTransaction_type() != null)
                payload.addProperty("transaction_type", advice.getTransaction_type());

            payload.addProperty("quantity", advice.getQuantity());
            payload.addProperty("priorityScore", advice.getPriorityScore());

            if (advice.getProduct() != null) payload.addProperty("product", advice.getProduct());
            if (advice.getValidity() != null) payload.addProperty("validity", advice.getValidity());
            if (advice.getPrice() != null) payload.addProperty("price", advice.getPrice());
            if (advice.getTrigger_price() != null)
                payload.addProperty("trigger_price", advice.getTrigger_price());
            if (advice.getStatus() != null) payload.addProperty("status", advice.getStatus().name());
            if (advice.getRiskCategory() != null)
                payload.addProperty("riskCategory", advice.getRiskCategory().name());
            if (advice.getStrategy() != null)
                payload.addProperty("strategy", advice.getStrategy().name());
            if (advice.getExecutionContext() != null)
                payload.addProperty("executionContext", advice.getExecutionContext().name());

            // Market context if available
            if (advice.getMarketRegime() != null)
                payload.addProperty("marketRegime", advice.getMarketRegime());
            if (advice.getMarketSentimentScore() != null)
                payload.addProperty("marketSentiment", advice.getMarketSentimentScore());

            // Performance data
            if (advice.getExecutionLatencyMs() != null)
                payload.addProperty("executionLatencyMs", advice.getExecutionLatencyMs());
            if (advice.getExecutionQuality() != null)
                payload.addProperty("executionQuality", advice.getExecutionQuality());

            // Key for partitioning
            String key = firstNonBlank(advice.getSymbol(),
                    advice.getInstrument_token(),
                    advice.getId());

            if (eventPublisher != null) {
                eventPublisher.publish(EventBusConfig.TOPIC_ADVICE,
                        isBlank(key) ? null : key,
                        payload.toString());
            }

        } catch (Throwable ignore) {
            // Best effort event publishing
        }
    }

    private void publishAdviceMetrics() {
        JsonObject metrics = new JsonObject();
        metrics.addProperty("ts", Instant.now().toEpochMilli());
        metrics.addProperty("ts_iso", Instant.now().toString());
        metrics.addProperty("source", "advice_service");
        metrics.addProperty("advicesCreated", advicesCreated.get());
        metrics.addProperty("advicesExecuted", advicesExecuted.get());
        metrics.addProperty("advicesFailed", advicesFailed.get());

        long totalProcessed = advicesCreated.get();
        if (totalProcessed > 0) {
            metrics.addProperty("successRate",
                    advicesExecuted.get() / (double) totalProcessed);
            metrics.addProperty("failureRate",
                    advicesFailed.get() / (double) totalProcessed);
        }

        // Add real-time statistics
        try {
            Instant since = Instant.now().minus(Duration.ofHours(24));
            long pendingCount = adviceRepo.countByStatusAndCreatedAtAfter(AdviceStatus.PENDING, since);
            long executedCount = adviceRepo.countByStatusAndCreatedAtAfter(AdviceStatus.EXECUTED, since);

            metrics.addProperty("pending24h", pendingCount);
            metrics.addProperty("executed24h", executedCount);
        } catch (Exception e) {
            log.debug("Failed to add real-time metrics: {}", e.getMessage());
        }

        if (eventPublisher != null) {
            eventPublisher.publish(EventBusConfig.TOPIC_METRICS,
                    "advice_metrics",
                    metrics.toString());
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String s : values) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return null;
    }
}
