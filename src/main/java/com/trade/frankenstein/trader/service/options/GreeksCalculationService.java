package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.dto.RealTimeGreeksDTO;
import com.trade.frankenstein.trader.model.documents.GreeksSnapshot;
import com.upstox.api.MarketQuoteOptionGreekV3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface GreeksCalculationService {

    Map<String, MarketQuoteOptionGreekV3> calculateGreeksBulk(List<String> instrumentKeys);

    MarketQuoteOptionGreekV3 calculateGreeks(String instrumentKey);

    CompletableFuture<Map<String, RealTimeGreeksDTO>> calculateGreeksAsync(List<String> instrumentKeys);

    GreeksSnapshot saveGreeksSnapshot(String instrumentKey, MarketQuoteOptionGreekV3 greeks);

    List<GreeksSnapshot> getHistoricalGreeks(String instrumentKey, int days);

    Map<String, RealTimeGreeksDTO> enrichWithHistoricalContext(Map<String, RealTimeGreeksDTO> greeksMap);

    void validateGreeksData(MarketQuoteOptionGreekV3 greeks) throws IllegalArgumentException;
}
