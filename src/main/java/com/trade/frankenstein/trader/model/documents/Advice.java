package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.StrategyName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Includes UI fields + order draft fields aligned with PlaceOrderRequest.
 */
@Document("advices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Advice {

    @Id
    private String id;

    // UI/meta
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;

    private String symbol;            // display
    private Integer confidence;       // 0..100
    private Integer tech;             // 0..100
    private Integer news;             // 0..100
    private String reason;            // short line
    private AdviceStatus status;      // PENDING/EXECUTED/DISMISSED
    private String order_id;          // set once executed
    private StrategyName strategy;    // optional

    // --- Order draft (match PlaceOrderRequest members & names) ---
    private String instrument_token;
    private String order_type;          // MARKET/LIMIT/SL
    private String transaction_type;    // BUY/SELL
    private int quantity;
    private String product;             // MIS/NRML/CNC
    private String validity;            // DAY/IOC
    private double price;
    private String tag;
    private int disclosed_quantity;
    private double trigger_price;
    private boolean is_amo;
    private boolean slice;
}
