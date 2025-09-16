package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.model.upstox.OHLC_Quotes;
import com.trade.frankenstein.trader.repo.documents.MarketSentimentSnapshotRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@Slf4j
public class SentimentService {

    @Autowired
    private MarketSentimentSnapshotRepo sentimentRepo;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private UpstoxService upstox;
    @Autowired
    private MarketDataService marketData;

    /**
     * Underlying key for the market snapshot (index). You can override via config.
     */
    @Value("${trade.nifty-underlying-key:NFO:NIFTY50-INDEX}")
    private String underlyingKey;

    /**
     * Refresh cadence in ms (default: 60s).
     */
    @Value("${trade.sentiment.refresh-ms:60000}")
    private long refreshMs;


    // =================================================================================
    // Read latest (same as before)
    // =================================================================================
    @Transactional(readOnly = true)
    public Result<MarketSentimentSnapshot> getNow() {
        MarketSentimentSnapshot snap = sentimentRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"), Sort.Order.desc("updatedAt"))))
                .stream().findFirst().orElse(null);

        if (snap == null) snap = neutral();
        try {
            stream.send("sentiment.update", snap);
        } catch (Throwable ignored) {
        }
        return Result.ok(snap);
    }

    // =================================================================================
    // Realtime: compute from live data, SAVE, broadcast
    // =================================================================================
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MarketSentimentSnapshot captureRealtimeNow() {
        MarketSentimentSnapshot s = buildFromRealtime(underlyingKey);
        MarketSentimentSnapshot saved = sentimentRepo.save(s);
        try {
            stream.send("sentiment.update", saved);
        } catch (Throwable ignored) {
        }
        return saved;
    }

    /**
     * Scheduled realtime refresh (every refreshMs).
     */
    @Scheduled(fixedDelayString = "${trade.sentiment.refresh-ms:60000}")
    @Transactional
    public void refreshRealtimeScheduled() {
        try {
            captureRealtimeNow();
        } catch (Throwable t) {
            log.debug("refreshRealtimeScheduled failed: {}", t.getMessage());
        }
    }

    // =================================================================================
    // Builders
    // =================================================================================
    private MarketSentimentSnapshot neutral() {
        MarketSentimentSnapshot s = new MarketSentimentSnapshot();
        s.setAsOf(Instant.now());
        s.setSentiment("Neutral");
        s.setScore(50);
        s.setConfidence(50);
        s.setPredictionAccuracy(0);
        s.setEmoji("😐");
        return s;
    }

    /**
     * Build a snapshot by blending momentum Z and current 1m bar microstructure.
     */
    private MarketSentimentSnapshot buildFromRealtime(String instrumentKey) {
        // 1) Live OHLC (1-minute)
        double momBarPct = 0.0; // % move in the current 1-min bar
        double roughness = 0.0; // % range of the bar (proxy for noise/vol)
        try {
            OHLC_Quotes q = upstox.getMarketOHLCQuote(instrumentKey, "1minute");
            if (q != null && q.getData() != null) {
                OHLC_Quotes.OHLCData d = q.getData().get(instrumentKey);
                if (d != null && d.getLive_ohlc() != null) {
                    OHLC_Quotes.Ohlc o = d.getLive_ohlc();
                    double open = o.getOpen();
                    double high = o.getHigh();
                    double low = o.getLow();
                    double close = o.getClose();
                    if (open > 0.0) momBarPct = ((close - open) / open) * 100.0;
                    double mid = (high + low) / 2.0;
                    if (mid > 0.0 && high >= low) roughness = ((high - low) / mid) * 100.0;
                }
            }
        } catch (Throwable t) {
            log.debug("buildFromRealtime: OHLC read failed: {}", t.getMessage());
        }

        // 2) Momentum Z-score (from MarketDataService)
        double z = 0.0;
        try {
            Result<BigDecimal> zRes = marketData.getMomentumNow(Instant.now());
            if (zRes != null && zRes.isOk() && zRes.get() != null) {
                z = zRes.get().doubleValue();
            }
        } catch (Throwable t) {
            log.debug("buildFromRealtime: momentum z fetch failed: {}", t.getMessage());
        }

        // 3) Blend to score (0..100)
        //    - map z in ~[-2, +2] to 0..100 (50 + z*20)
        //    - add a small kicker from the live bar (%), damped by roughness
        int zScore = clamp0to100((int) Math.round(50 + z * 20.0));
        double barKicker = momBarPct * Math.max(0.0, 1.0 - Math.min(1.0, roughness / 1.0)); // roughness>1% reduces kicker
        int score = clamp0to100((int) Math.round(zScore + barKicker));

        // 4) Confidence from |z| and (low roughness)
        int confidence = 50;
        double absZ = Math.abs(z);
        if (absZ >= 1.0) confidence += 20;
        else if (absZ >= 0.6) confidence += 10;
        if (roughness <= 0.3) confidence += 10;           // calm bar
        else if (roughness >= 1.0) confidence -= 10;      // choppy
        confidence = clamp0to100(confidence);

        // 5) Sentiment label + emoji
        String label;
        String emoji;
        if (score >= 60) {
            label = "Bullish";
            emoji = "🟢";
        } else if (score <= 40) {
            label = "Bearish";
            emoji = "🔴";
        } else {
            label = "Neutral";
            emoji = "😐";
        }

        // 6) Build document
        MarketSentimentSnapshot s = new MarketSentimentSnapshot();
        s.setAsOf(Instant.now());
        s.setScore(score);
        s.setConfidence(confidence);
        s.setPredictionAccuracy(0); // fill from backtest live later if you track hit-rate
        s.setSentiment(label);
        s.setEmoji(emoji);
        return s;
    }

    // =================================================================================
    // Small helpers
    // =================================================================================
    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

}
