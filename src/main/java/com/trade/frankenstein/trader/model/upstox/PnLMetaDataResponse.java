package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PnLMetaDataResponse {

    private String status;
    private PnLMetaData data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PnLMetaData {
        private int trades_count;
        private int page_size_limit;
    }
}
