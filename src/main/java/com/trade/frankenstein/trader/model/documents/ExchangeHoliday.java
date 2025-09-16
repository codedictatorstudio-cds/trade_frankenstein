package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

/**
 * Exchange holiday list
 */
@Document("exchange_holidays")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeHoliday {

    private @Id String id;

    private LocalDate date;
    private String name;
}
