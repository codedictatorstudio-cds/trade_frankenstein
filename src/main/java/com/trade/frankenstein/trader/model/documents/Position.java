package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("positions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    private @Id String id;

    private String accountId;

    private String symbol;
    private long instrumentToken;

    private Integer quantity;     // signed
    private double avgPrice;
    private double lastPrice;

    private double unrealizedPnl;
    private double realizedPnl;

    private String product;       // MIS/NRML/CNC
}
