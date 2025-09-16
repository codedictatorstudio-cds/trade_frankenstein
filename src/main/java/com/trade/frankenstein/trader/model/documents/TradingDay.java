package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

/**
 * Trading calendar support (optional but handy)
 */
@Document("trading_days")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingDay {

    private @Id String id;

    private LocalDate date;
    private boolean trading;    // true if market open
}
