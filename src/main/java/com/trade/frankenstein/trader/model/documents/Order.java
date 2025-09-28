package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;


@Document("orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private String id;

    // Broker / linkage
    private String order_id;             // broker order id
    private String exchange_order_id;    // if provided
    private String parent_order_id;      // for slice/BO/CO
    private String advice_id;            // originating Advice.id (optional)

    // Instrument & draft (match PlaceOrderRequest)
    private String instrument_token;
    private String symbol;
    private String order_type;           // MARKET/LIMIT/SL
    private String transaction_type;     // BUY/SELL
    private Integer quantity;
    private String product;              // MIS/NRML/CNC
    private String validity;             // DAY/IOC
    private double price;
    private double trigger_price;
    private int disclosed_quantity;
    private boolean is_amo;
    private boolean slice;
    private String tag;

    // Lifecycle & fills
    private OrderStatus status;          // OPEN/COMPLETE/CANCELLED/REJECTED/...
    private int filled_quantity;
    private int pending_quantity;
    private double average_price;
    private String message;              // broker message
    private String rejection_reason;

    // Timestamps
    private Instant placed_at;
    private Instant updated_at;
    private Instant exchange_ts;
}
