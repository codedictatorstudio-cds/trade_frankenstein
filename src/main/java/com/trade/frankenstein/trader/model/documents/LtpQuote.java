package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mirrors LTP_Quotes.LTPQuoteData fields
 */
@Document("ltp_quotes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LtpQuote {

    private @Id String id;

    private double last_price;
    private String instrument_token;
    private int ltq;
    private int volume;
    private double cp;
}
