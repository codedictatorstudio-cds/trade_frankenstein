package com.trade.frankenstein.trader.model.documents;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Document
@Data
public class MarketMicrostructure {

    @Id
    private String id;
    private String symbol;
    private OrderBookDepth depth;
    private BigDecimal imbalance; // -1.0 to 1.0
    private BigDecimal liquidityScore; // 0.0 to 1.0
    private List<PriceLevel> levels;
    private BigDecimal spread;
    private BigDecimal effectiveSpread;
    private Long totalVolume;
    private Instant timestamp;
}
