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
public class MarketHolidays {

    private String status;
    private List<Holiday> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class Holiday {
        private String date;
        private String description;
        private String holiday_type;
        private List<ExchangeSession> closed_exchanges;
        private List<ExchangeSession> open_exchanges;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class ExchangeSession {
        private String exchange;
        private long start_time;
        private long end_time;
    }
}
