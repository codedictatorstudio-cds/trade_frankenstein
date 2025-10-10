package com.trade.frankenstein.trader.model.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mirrors OptionsInstruments.OptionInstrument fields
 */
@Document("option_instruments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionInstrument {

    private @Id String id;

    private String name;
    private String segment;
    private String exchange;
    private String expiry;            // kept as String to match model
    private String instrument_key;
    private String exchange_token;
    private String trading_symbol;
    private int tick_size;
    private int lot_size;
    private String instrument_type;
    private int freeze_quantity;
    private String underlying_key;
    private String underlying_type;
    private String underlying_symbol;
    private int strike_price;
    private int minimum_lot;
    private boolean weekly;
}
