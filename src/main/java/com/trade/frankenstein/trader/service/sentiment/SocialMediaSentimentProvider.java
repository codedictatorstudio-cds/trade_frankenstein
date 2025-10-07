package com.trade.frankenstein.trader.service.sentiment;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class SocialMediaSentimentProvider implements SentimentProvider {

    private final SocialMediaApiClient client;

    public SocialMediaSentimentProvider(SocialMediaApiClient client) {
        this.client = client;
    }

    @Override
    public Optional<BigDecimal> fetchSentiment() {
        // Example: fetch Twitter polarity score (-1..+1), map to 0..100
        Double polarity = client.getRecentPolarity().orElse(0.0);
        BigDecimal mapped = BigDecimal.valueOf((polarity + 1) * 50);
        return Optional.of(clip(mapped, BigDecimal.ZERO, BigDecimal.valueOf(100)));
    }

    private static BigDecimal clip(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v == null) return null;
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }
}
