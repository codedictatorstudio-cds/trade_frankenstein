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
public class MarketDataFeed {

    private String guid;
    private String method;
    private MarketDataFeedData data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MarketDataFeedData {
        private String mode;
        private List<String> instrumentKeys;
    }
}
