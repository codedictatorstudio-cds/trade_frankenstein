package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.dto.SignalDTO;
import com.trade.frankenstein.trader.dto.TradingSignal;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderStatus;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.documents.Order;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class TradeAuditLogger {
    private static final Logger logger = LoggerFactory.getLogger(TradeAuditLogger.class);

    @Autowired
    private TradeRepo tradeRepo;

    /**
     * Log a trade decision made by signal analysis
     */
    public void logTradeDecision(String signalType, Map<String, Object> context, String decision) {
        try {
            Trade auditTrade = Trade.builder()
                    .symbol(extractSymbol(context))
                    .side(extractSide(context))
                    .quantity(extractQuantity(context))
                    .entryPrice(extractPrice(context))
                    .status(TradeStatus.OPEN) // Assuming OPEN status for new signals
                    .explain("Signal: " + signalType + " | Decision: " + decision + " | Context: " + context.toString())
                    .entryTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(auditTrade);
            logger.info("AUDIT: Signal={}, Decision={}, Symbol={}, Context={}",
                    signalType, decision, auditTrade.getSymbol(), context);

        } catch (Exception e) {
            logger.error("Failed to log trade decision for signal: " + signalType, e);
        }
    }

    /**
     * Log order execution details using Order object
     */
    public void logOrderExecution(Order order, String action, Map<String, Object> details) {
        try {
            Trade executionTrade = Trade.builder()
                    .order_id(order.getOrder_id())
                    .brokerTradeId(order.getExchange_order_id())
                    .symbol(order.getSymbol())
                    .side(mapTransactionTypeToOrderSide(order.getTransaction_type()))
                    .quantity(order.getQuantity())
                    .entryPrice(order.getPrice())
                    .currentPrice(order.getAverage_price())
                    .status(mapOrderStatusToTradeStatus(order.getStatus()))
                    .explain("Order Action: " + action + " | Details: " + details.toString())
                    .entryTime(order.getPlaced_at())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(executionTrade);
            logger.info("ORDER_AUDIT: OrderId={}, Action={}, Symbol={}, Details={}",
                    order.getOrder_id(), action, order.getSymbol(), details);

        } catch (Exception e) {
            logger.error("Failed to log order execution for orderId: " + order.getOrder_id(), e);
        }
    }

    /**
     * Log order execution with orderId only
     */
    public void logOrderExecution(String orderId, String action, Map<String, Object> details) {
        try {
            Trade executionTrade = Trade.builder()
                    .order_id(orderId)
                    .symbol(extractSymbol(details))
                    .side(extractSide(details))
                    .quantity(extractQuantity(details))
                    .entryPrice(extractPrice(details))
                    .status(getTradeStatusFromAction(action))
                    .explain("Order Action: " + action + " | Details: " + details.toString())
                    .entryTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(executionTrade);
            logger.info("ORDER_AUDIT: OrderId={}, Action={}, Details={}",
                    orderId, action, details);

        } catch (Exception e) {
            logger.error("Failed to log order execution for orderId: " + orderId, e);
        }
    }

    /**
     * Log risk events and violations
     */
    public void logRiskEvent(String eventType, String description, Map<String, Object> metrics) {
        try {
            Trade riskTrade = Trade.builder()
                    .symbol(extractSymbol(metrics))
                    .pnl(extractPnL(metrics))
                    .status(TradeStatus.CANCELLED) // Using CANCELLED for risk events
                    .explain("Risk Event: " + eventType + " | Description: " + description + " | Metrics: " + metrics.toString())
                    .entryTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(riskTrade);
            logger.warn("RISK_AUDIT: Event={}, Description={}, Metrics={}",
                    eventType, description, metrics);

        } catch (Exception e) {
            logger.error("Failed to log risk event: " + eventType, e);
        }
    }

    /**
     * Log TradingSignal generation (using actual TradingSignal DTO)
     */
    public void logTradingSignalGeneration(TradingSignal signal) {
        try {
            Trade signalTrade = Trade.builder()
                    .symbol(signal.getInstrumentKey()) // TradingSignal uses instrumentKey
                    .side(mapActionToOrderSide(signal.getAction()))
                    .entryPrice(signal.getEntryPrice() != null ? signal.getEntryPrice().doubleValue() : null)
                    .status(TradeStatus.OPEN)
                    .explain("Trading Signal: " + signal.getAction() +
                            " | Strength: " + signal.getStrength() +
                            " | Confidence: " + signal.getConfidence() +
                            " | Entry: " + signal.getEntryPrice() +
                            " | StopLoss: " + signal.getStopLoss() +
                            " | TakeProfit: " + signal.getTakeProfit())
                    .entryTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(signalTrade);
            logger.info("TRADING_SIGNAL_AUDIT: Action={}, Instrument={}, Strength={}, Confidence={}",
                    signal.getAction(), signal.getInstrumentKey(), signal.getStrength(), signal.getConfidence());

        } catch (Exception e) {
            logger.error("Failed to log trading signal for: " + signal.getInstrumentKey(), e);
        }
    }

    /**
     * Log SignalDTO generation (using actual SignalDTO record)
     */
    public void logSignalGeneration(SignalDTO signal) {
        try {
            Trade signalTrade = Trade.builder()
                    .symbol(signal.instrumentKey())
                    .side(mapDirectionToOrderSide(signal.direction()))
                    .status(TradeStatus.OPEN)
                    .explain("Signal: " + signal.signalType() +
                            " | Value: " + signal.value() +
                            " | Confidence: " + signal.confidence() +
                            " | Direction: " + signal.direction() +
                            " | Strength: " + signal.strength() +
                            " | Metadata: " + signal.metadata())
                    .entryTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(signalTrade);
            logger.info("SIGNAL_AUDIT: Type={}, Instrument={}, Direction={}, Confidence={}",
                    signal.signalType(), signal.instrumentKey(), signal.direction(), signal.confidence());

        } catch (Exception e) {
            logger.error("Failed to log signal generation for: " + signal.instrumentKey(), e);
        }
    }

    /**
     * Log PnL updates
     */
    public void logPnLUpdate(String symbol, Double pnl, Map<String, Object> portfolioState) {
        try {
            Trade pnlTrade = Trade.builder()
                    .symbol(symbol)
                    .pnl(pnl)
                    .status(TradeStatus.PARTIAL) // Using PARTIAL for PnL updates
                    .explain("PnL Update | Portfolio State: " + portfolioState.toString())
                    .entryTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            tradeRepo.save(pnlTrade);
            logger.info("PNL_AUDIT: Symbol={}, PnL={}", symbol, pnl);

        } catch (Exception e) {
            logger.error("Failed to log PnL update for symbol: " + symbol, e);
        }
    }

    // Helper methods to extract values from context maps
    private String extractSymbol(Map<String, Object> context) {
        return context.containsKey("symbol") ? context.get("symbol").toString() : "UNKNOWN";
    }

    private OrderSide extractSide(Map<String, Object> context) {
        if (context.containsKey("side")) {
            String side = context.get("side").toString().toUpperCase();
            return "BUY".equals(side) ? OrderSide.BUY : OrderSide.SELL;
        }
        return OrderSide.BUY; // Default
    }

    private Integer extractQuantity(Map<String, Object> context) {
        if (context.containsKey("quantity")) {
            return ((Number) context.get("quantity")).intValue();
        }
        return null;
    }

    private Double extractPrice(Map<String, Object> context) {
        if (context.containsKey("price")) {
            return ((Number) context.get("price")).doubleValue();
        }
        return null;
    }

    private Double extractPnL(Map<String, Object> context) {
        if (context.containsKey("pnl")) {
            return ((Number) context.get("pnl")).doubleValue();
        }
        return null;
    }

    // Mapping methods
    private OrderSide mapTransactionTypeToOrderSide(String transactionType) {
        return "BUY".equals(transactionType) ? OrderSide.BUY : OrderSide.SELL;
    }

    private OrderSide mapActionToOrderSide(String action) {
        return "BUY".equals(action) ? OrderSide.BUY : OrderSide.SELL;
    }

    private OrderSide mapDirectionToOrderSide(String direction) {
        return "BUY".equals(direction) ? OrderSide.BUY : OrderSide.SELL;
    }

    private TradeStatus mapOrderStatusToTradeStatus(OrderStatus orderStatus) {
        // Map your OrderStatus enum to TradeStatus enum
        switch (orderStatus) {
            case COMPLETE:
                return TradeStatus.CLOSED;
            case CANCELLED, REJECTED:
                return TradeStatus.CANCELLED;
            case OPEN:
            default:
                return TradeStatus.OPEN;
        }
    }

    private TradeStatus getTradeStatusFromAction(String action) {
        switch (action.toUpperCase()) {
            case "PLACE_ORDER":
                return TradeStatus.OPEN;
            case "ORDER_FILLED":
                return TradeStatus.CLOSED;
            case "ORDER_CANCELLED", "ORDER_REJECTED":
                return TradeStatus.CANCELLED;
            default:
                return TradeStatus.PARTIAL;
        }
    }
}
