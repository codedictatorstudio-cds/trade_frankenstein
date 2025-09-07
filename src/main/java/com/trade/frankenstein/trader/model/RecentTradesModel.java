package com.trade.frankenstein.trader.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public final class RecentTradesModel {

    private List<TradeRow> items;
    private Instant asOf; // optional

}
