package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("candles_1m")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"symbol", "openTime"})
public class Candle {

    @Id
    private String id;

    @NonNull
    private String symbol;

    @NonNull
    private Instant openTime;   // start of minute

    private double openPrice;

    private double highPrice;

    private double lowPrice;

    private double closePrice;

    private Long volume;
}
