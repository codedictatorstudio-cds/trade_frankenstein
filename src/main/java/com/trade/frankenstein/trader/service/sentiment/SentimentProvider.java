package com.trade.frankenstein.trader.service.sentiment;

import java.math.BigDecimal;
import java.util.Optional;

public interface SentimentProvider {
    /**
     * Fetches a normalized sentiment score (0..100).
     */
    Optional<BigDecimal> fetchSentiment();
}
