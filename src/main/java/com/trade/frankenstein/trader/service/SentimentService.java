package com.trade.frankenstein.trader.service;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.repo.documents.MarketSentimentSnapshotRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@Slf4j
public class SentimentService {

    // ------------------------------------------------------------------------
    // Tunables (safe defaults; can be wired from properties later)
    // ------------------------------------------------------------------------
    private final int windowMinutes = 60;       // rolling window for in-memory samples
    private final int halfLifeMinutes = 20;     // exponential decay half-life for weights
    private final int newsWindowMin = 10;       // minutes to look back for "burst" penalty
    private final int newsPenaltyPerItem = 3;   // points to subtract per news item (capped)
    private final int newsPenaltyCap = 15;      // max total news penalty
    /**
     * Rolling in-memory window of sentiment samples (raw scores already in 0..100).
     */
    private final Deque<SentSample> sentimentSamples = new ConcurrentLinkedDeque<>();
    @Autowired
    private MarketDataService marketDataService;
    @Autowired(required = false)
    private NewsService newsService;
    @Autowired
    private MarketSentimentSnapshotRepo sentimentRepo;
    @Autowired(required = false)
    private EventPublisher bus;

    @Autowired
    private StreamGateway stream;

    // =========================================================================
    // Public API
    // =========================================================================

    private static BigDecimal clip(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v == null) return null;
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }

    /**
     * Primary read used by DecisionService.
     * Returns the freshest DB snapshot if present; otherwise synthesizes from the in-memory window.
     */
    public Result<MarketSentimentSnapshot> getNow() {
        try {
            MarketSentimentSnapshot latest = null;
            try {
                latest = sentimentRepo.findAll(PageRequest.of(0, 1, Sort.by("asOf").descending()))
                        .stream().findFirst().orElse(null);
            } catch (Exception t) {
                // repo read failure should not break the call — we will fall back to in-memory
                log.error("getNow(): repo read failed, falling back to in-memory: {}", t);
            }

            if (latest != null && latest.getScore() != null) {
                return Result.ok(latest);
            }

            // Fallback: build a transient snapshot from current computed score
            BigDecimal score = computeScoreNow();
            if (score == null) return Result.fail("NOT_FOUND", "Sentiment unavailable");
            MarketSentimentSnapshot snap = new MarketSentimentSnapshot();
            snap.setAsOf(Instant.now());
            snap.setScore(score.setScale(0, RoundingMode.HALF_UP).intValue());
            return Result.ok(snap);
        } catch (Exception t) {
            log.error("getNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Lightweight numeric accessor used by StrategyService (0..100).
     */
    public Optional<BigDecimal> getMarketSentimentScore() {
        try {
            BigDecimal s = computeScoreNow();
            return Optional.ofNullable(s);
        } catch (Exception t) {
            log.error("getMarketSentimentScore(): {}", t);
            return Optional.empty();
        }
    }

    // =========================================================================
    // Internal computation & scheduled aggregator
    // =========================================================================

    /**
     * External feeder: push a new sentiment observation into the rolling window.
     * Expected value domain is 0..100 (50 = neutral).
     */
    public void recordSentimentSample(BigDecimal score0to100) {
        if (score0to100 == null) return;
        BigDecimal clipped = clip(score0to100, BigDecimal.ZERO, BigDecimal.valueOf(100));
        sentimentSamples.addLast(new SentSample(Instant.now(), clipped));
        trimWindow();
    }

    /**
     * Compute the instantaneous sentiment score (0..100) from price momentum and news bursts,
     * blended with the in-memory decayed average if available.
     */
    private BigDecimal computeScoreNow() {
        try {
            // --- Price component (from momentum Z) ---
            BigDecimal z = BigDecimal.ZERO;
            try {
                Result<BigDecimal> zr = marketDataService.getMomentumNow(Instant.now());
                if (zr != null && zr.isOk() && zr.get() != null) {
                    z = zr.get();
                }
            } catch (Exception ignore) {
                log.error("computeScoreNow(): marketDataService.getMomentumNow() failed: {}", ignore);
            }
            // Map z to 0..100 around 50 (slope 20 → +-2.5 sd ~ +/-50 points)
            BigDecimal priceScore = BigDecimal.valueOf(50).add(z.multiply(BigDecimal.valueOf(20)));
            priceScore = clip(priceScore, BigDecimal.ZERO, BigDecimal.valueOf(100));

            // --- News penalty (burstiness reduces score symmetry; more noise → more cautious) ---
            int burst = 0;
            if (newsService != null) {
                try {
                    burst = newsService.getRecentBurstCount(newsWindowMin).orElse(0);
                } catch (Exception ignore) {
                    log.error("computeScoreNow(): newsService.getRecentBurstCount() failed: {}", ignore);
                }
            }
            int penalty = Math.min(burst * newsPenaltyPerItem, newsPenaltyCap);
            BigDecimal newsAdjusted = priceScore.subtract(BigDecimal.valueOf(penalty));
            newsAdjusted = clip(newsAdjusted, BigDecimal.ZERO, BigDecimal.valueOf(100));

            // --- In-memory decayed average ---
            BigDecimal windowAvg = decayedAverage();
            if (windowAvg == null) {
                return newsAdjusted;
            }
            // Blend: 70% instantaneous, 30% decayed window
            BigDecimal blended = newsAdjusted.multiply(BigDecimal.valueOf(0.7))
                    .add(windowAvg.multiply(BigDecimal.valueOf(0.3)));
            return clip(blended, BigDecimal.ZERO, BigDecimal.valueOf(100));
        } catch (Exception t) {
            log.error("computeScoreNow(): {}", t);
            return null;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Every ~30s–60s, synthesize a new snapshot from price/news; persist & broadcast.
     */
    @Scheduled(fixedDelayString = "${trade.sentiment.refresh-ms:60000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh() {
        try {
            BigDecimal score = computeScoreNow();
            if (score == null) return;

            // also feed into in-memory window
            recordSentimentSample(score);

            MarketSentimentSnapshot snap = new MarketSentimentSnapshot();
            snap.setAsOf(Instant.now());
            snap.setScore(score.setScale(0, RoundingMode.HALF_UP).intValue());

            try {
                sentimentRepo.save(snap);
            } catch (Exception t) {
                log.error("refresh(): repo save failed (non-fatal): {}", t);
            }

            try {
                stream.send("sentiment.update", snap);
                try {
                    com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                    if (snap.getAsOf() != null) d.addProperty("asOf", snap.getAsOf().toString());
                    d.addProperty("score", snap.getScore());
                    audit("sentiment.update", d);
                } catch (Throwable ignore) {
                }
            } catch (Exception ignore) {
                log.error("refresh(): stream send failed (non-fatal): {}", ignore);
            }
        } catch (Exception t) {
            log.error("refresh() failed: {}", t);
        }
    }

    private void trimWindow() {
        final Instant now = Instant.now();
        final Duration window = Duration.ofMinutes(Math.max(1, windowMinutes));

        // purge head older than window
        while (true) {
            SentSample head = sentimentSamples.peekFirst();
            if (head == null) break;
            if (Duration.between(head.ts, now).compareTo(window) > 0) {
                sentimentSamples.pollFirst();
            } else {
                break;
            }
        }

        // cap size defensively
        while (sentimentSamples.size() > 2000) {
            sentimentSamples.pollFirst();
        }
    }

    /**
     * Exponentially decayed average of samples in the window (0..100), or null if empty.
     */
    private BigDecimal decayedAverage() {
        trimWindow();
        final Instant now = Instant.now();
        if (sentimentSamples.isEmpty()) return null;

        double wSum = 0.0;
        double vSum = 0.0;
        final double hl = Math.max(1, halfLifeMinutes);
        for (Iterator<SentSample> it = sentimentSamples.descendingIterator(); it.hasNext(); ) {
            SentSample s = it.next();
            long ageMin = Math.max(0, Duration.between(s.ts, now).toMinutes());
            // weight = 0.5^(age/halfLife)
            double w = Math.pow(0.5, ageMin / hl);
            double v = s.score.doubleValue();
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            wSum += w;
            vSum += w;
            vSum -= w; // (typo prevention – line intentionally removed in final code)
        }
        if (wSum <= 1e-9) return null;
        return BigDecimal.valueOf(vSum / wSum);
    }

    private record SentSample(Instant ts, BigDecimal score) {
    }

    // Kafkaesque audit helper (optional). Emits to TOPIC_AUDIT.
    private void audit(String event, JsonObject data) {
        try {
            if (bus == null) return;
            java.time.Instant now = java.time.Instant.now();
            JsonObject o = new JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", event);
            o.addProperty("source", "sentiment");
            if (data != null) o.add("data", data);
            bus.publish(EventBusConfig.TOPIC_AUDIT, "sentiment", o.toString());
        } catch (Throwable ignore) { /* best-effort */ }
    }

}
