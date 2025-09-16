package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mirrors OHLC_Quotes (OHLCData + Ohlc) essentials
 */
@Document("ohlc_quotes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OhlcQuote {

    private @Id String id;

    private double last_price;
    private Ohlc prev_ohlc;
    private Ohlc live_ohlc;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ohlc {
        private double open;
        private double high;
        private double low;
        private double close;
        private int volume;
        private long ts;
    }
}
