package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.StrategyName;
import com.trade.frankenstein.trader.enums.TradeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("trades")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trade {

    @Id
    private String id;

    private String order_id;         // broker/internal ref
    private String brokerTradeId;

    private String symbol;
    private OrderSide side;          // BUY/SELL
    private Integer quantity;

    private Double entryPrice;
    private Double currentPrice;
    private Double pnl;

    private TradeStatus status;      // OPEN/CLOSED/PARTIAL/CANCELLED
    private StrategyName strategy;

    private Instant entryTime;
    private Instant exitTime;
    private Long durationMs;

    private String explain;          // lightweight “Why?”

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
