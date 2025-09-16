package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("ticks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tick {

    private @Id String id;

    private String symbol;
    private Instant ts;

    private double ltp;
    private Long quantity;
}
