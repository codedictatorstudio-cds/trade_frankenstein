package com.trade.frankenstein.trader.service.sentiment;

import com.trade.frankenstein.trader.service.news.NewsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service("newsSentimentProvider")
public class NewsSentimentProvider implements SentimentProvider {

    @Autowired
    private NewsService newsService;

    @Value("${news.sentiment.window-min:30}")
    public int windowMin;
    @Value("${news.sentiment.penalty-per-item:5}")
    public int penaltyPerItem;
    @Value("${news.sentiment.penalty-cap:50}")
    public int penaltyCap;


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
