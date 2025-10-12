package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.dto.OptionChainAnalyticsDTO;
import com.trade.frankenstein.trader.model.documents.OptionChainAnalytics;
import com.trade.frankenstein.trader.model.documents.VolatilitySurface;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface OptionChainAnalyticsService {
    OptionChainAnalyticsDTO calculateAnalytics(String underlyingKey, LocalDate expiry);

    VolatilitySurface buildVolatilitySurface(String underlyingKey, LocalDate expiry);

    BigDecimal calculateMaxPain(String underlyingKey, LocalDate expiry);

    BigDecimal calculateGammaExposure(String underlyingKey, LocalDate expiry);

    List<OptionChainAnalyticsDTO.OiChangeDTO> getTopOiChanges(String underlyingKey, LocalDate expiry, int limit);

    OptionChainAnalytics saveAnalyticsSnapshot(OptionChainAnalyticsDTO dto);

    List<OptionChainAnalytics> getHistoricalAnalytics(String underlyingKey, int days);

    void cleanupOldAnalytics(int retentionDays);
}
