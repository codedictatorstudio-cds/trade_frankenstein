package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OptionsInstruments {

    private String status;
    private List<OptionInstrument> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class OptionInstrument {
        private String name;
        private String segment;
        private String exchange;
        private String expiry;
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
}
