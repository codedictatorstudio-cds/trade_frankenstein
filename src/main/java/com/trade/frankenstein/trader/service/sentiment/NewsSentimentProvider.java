package com.trade.frankenstein.trader.service.sentiment;

import com.trade.frankenstein.trader.service.NewsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class NewsSentimentProvider implements SentimentProvider {
    private final NewsService newsService;
    private final int windowMin;
    private final int penaltyPerItem;
    private final int penaltyCap;

    public NewsSentimentProvider(NewsService newsService,
                                 int windowMin,
                                 int penaltyPerItem,
                                 int penaltyCap) {
        this.newsService = newsService;
        this.windowMin = windowMin;
        this.penaltyPerItem = penaltyPerItem;
        this.penaltyCap = penaltyCap;
    }

    @Override
    public Optional<BigDecimal> fetchSentiment() {
        int burst = newsService.getRecentBurstCount(windowMin).orElse(0);
        int penalty = Math.min(burst * penaltyPerItem, penaltyCap);
        // Map zero penalty => neutral 50; full penalty => lower bound 0
        BigDecimal score = BigDecimal.valueOf(50).subtract(BigDecimal.valueOf(penalty));
        return Optional.of(clip(score, BigDecimal.ZERO, BigDecimal.valueOf(100)));
    }

    private static BigDecimal clip(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v == null) return null;
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }
}
