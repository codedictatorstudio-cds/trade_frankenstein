package com.trade.frankenstein.trader.service.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.model.documents.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TradeEventPublisher {

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    public void publishTradeCreated(Trade trade) {
        publishTradeEvent("trade.created", trade);
    }

    public void publishTradeUpdated(Trade trade) {
        publishTradeEvent("trade.updated", trade);
    }

    public void publishTradeReconciled(Trade trade) {
        publishTradeEvent("trade.reconciled", trade);
    }

    public void publishTradeToDlq(Trade trade, String reason) {
        try {
            ObjectNode payload = createTradePayload(trade);
            payload.put("event", "trade.dlq");
            payload.put("reason", reason);

            eventPublisher.publish(EventBusConfig.TOPIC_TRADE,
                    trade.getSymbol(),
                    payload.toString());
        } catch (Exception e) {
            log.error("Failed to publish trade DLQ event for trade: {}", trade.getId(), e);
        }
    }

    private void publishTradeEvent(String eventType, Trade trade) {
        try {
            ObjectNode payload = createTradePayload(trade);
            payload.put("event", eventType);

            eventPublisher.publish(EventBusConfig.TOPIC_TRADE,
                    trade.getSymbol(),
                    payload.toString());
        } catch (Exception e) {
            log.error("Failed to publish {} event for trade: {}", eventType, trade.getId(), e);
        }
    }

    private ObjectNode createTradePayload(Trade trade) {
        ObjectNode payload = objectMapper.createObjectNode();

        // Trade identifiers
        payload.put("tradeId", trade.getId());
        payload.put("brokerTradeId", trade.getBrokerTradeId());
        payload.put("orderId", trade.getOrder_id());
        payload.put("symbol", trade.getSymbol());

        // Trade details
        payload.put("side", trade.getSide() != null ? trade.getSide().name() : null);
        payload.put("quantity", trade.getQuantity() != null ? trade.getQuantity() : 0);
        payload.put("entryPrice", trade.getEntryPrice());
        payload.put("currentPrice", trade.getCurrentPrice());
        payload.put("pnl", trade.getPnl());
        payload.put("status", trade.getStatus() != null ? trade.getStatus().name() : null);

        // Timestamps
        payload.put("entryTime", trade.getEntryTime() != null ? trade.getEntryTime().toString() : null);
        payload.put("exitTime", trade.getExitTime() != null ? trade.getExitTime().toString() : null);
        payload.put("updatedAt", trade.getUpdatedAt() != null ? trade.getUpdatedAt().toString() : null);
        payload.put("createdAt", trade.getCreatedAt() != null ? trade.getCreatedAt().toString() : null);

        return payload;
    }
}
