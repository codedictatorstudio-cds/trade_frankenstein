package com.trade.frankenstein.trader.service.sentiment;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.repo.documents.MarketSentimentSnapshotRepo;
import com.trade.frankenstein.trader.service.StreamGateway;
import com.trade.frankenstein.trader.service.market.MarketDataService;
import com.trade.frankenstein.trader.service.news.NewsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@Slf4j
public class SentimentService {

    private final int windowMinutes = 60;
    private final int halfLifeMinutes = 20;
    private final Deque<SentSample> sentimentSamples = new ConcurrentLinkedDeque<>();

    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private NewsService newsService;
    @Autowired
    private MarketSentimentSnapshotRepo sentimentRepo;
    @Autowired
    private PositionSizer positionSizer;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private EventPublisher bus;
    @Autowired
    private SentimentProvider newsSentimentProvider;
    @Autowired
    private SentimentProvider socialMediaSentimentProvider;

    // Multi-source providers
    private List<SentimentProvider> providers ;

    @PostConstruct
    public void initProviders() {
        providers = Arrays.asList(
                newsSentimentProvider,
                socialMediaSentimentProvider
                // Add more providers here
        );
    }


    private static BigDecimal clip(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v == null) return null;
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }

    // --- Public API ---

    public Result<MarketSentimentSnapshot> getNow() {
        try {
            MarketSentimentSnapshot latest = null;
            try {
                latest = sentimentRepo.findAll(PageRequest.of(0, 1, Sort.by("asOf").descending()))
                        .stream().findFirst().orElse(null);
            } catch (Exception t) {
                log.error("getNow(): repo read failed, falling back to in-memory: {}", t);
            }
            if (latest != null && latest.getScore() != null) {
                return Result.ok(latest);
            }
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

    public Optional<BigDecimal> getMarketSentimentScore() {
        try {
            BigDecimal s = computeScoreNow();
            return Optional.ofNullable(s);
        } catch (Exception t) {
            log.error("getMarketSentimentScore(): {}", t);
            return Optional.empty();
        }
    }

    public void recordSentimentSample(BigDecimal score0to100) {
        if (score0to100 == null) return;
        BigDecimal clipped = clip(score0to100, BigDecimal.ZERO, BigDecimal.valueOf(100));
        sentimentSamples.addLast(new SentSample(Instant.now(), clipped));
        trimWindow();
    }

    // --- Aggregating providers + price-based logic ---

    private BigDecimal computeScoreNow() {
        try {
            // 1. Price-based score (momentum Z → priceScore)
            BigDecimal z = BigDecimal.ZERO;
            try {
                Result zr = marketDataService.getMomentumNow(Instant.now());
                if (zr != null && zr.isOk() && zr.get() != null) {
                    z = (BigDecimal) zr.get();
                }
            } catch (Exception ignore) {
                log.error("computeScoreNow(): marketDataService error: {}", ignore);
            }
            BigDecimal priceScore = clip(BigDecimal.valueOf(50).add(z.multiply(BigDecimal.valueOf(20))),
                    BigDecimal.ZERO, BigDecimal.valueOf(100));

            // 2. Aggregate multi-source scores
            BigDecimal sumScores = BigDecimal.ZERO, sumWeights = BigDecimal.ZERO;
            for (SentimentProvider p : providers) {
                Optional<BigDecimal> opt = p.fetchSentiment();
                if (opt.isPresent()) {
                    BigDecimal s = opt.get();
                    BigDecimal w = BigDecimal.ONE; // You can customize this per source
                    sumScores = sumScores.add(s.multiply(w));
                    sumWeights = sumWeights.add(w);
                }
            }
            BigDecimal multiSourceScore = sumWeights.compareTo(BigDecimal.ZERO) > 0
                    ? sumScores.divide(sumWeights, 2, RoundingMode.HALF_UP)
                    : priceScore;

            // 3. Combine: 60% multi-source, 40% price
            BigDecimal blended = multiSourceScore.multiply(BigDecimal.valueOf(0.6))
                    .add(priceScore.multiply(BigDecimal.valueOf(0.4)));
            blended = clip(blended, BigDecimal.ZERO, BigDecimal.valueOf(100));

            // 4. Blend with decayed window: 70% blended, 30% in-memory average
            BigDecimal windowAvg = decayedAverage();
            if (windowAvg != null) {
                blended = blended.multiply(BigDecimal.valueOf(0.7))
                        .add(windowAvg.multiply(BigDecimal.valueOf(0.3)));
            }
            return clip(blended, BigDecimal.ZERO, BigDecimal.valueOf(100));
        } catch (Exception t) {
            log.error("computeScoreNow(): {}", t);
            return null;
        }
    }

    private void trimWindow() {
        final Instant now = Instant.now();
        final Duration window = Duration.ofMinutes(Math.max(1, windowMinutes));
        while (true) {
            SentSample head = sentimentSamples.peekFirst();
            if (head == null) break;
            if (Duration.between(head.ts, now).compareTo(window) > 0) {
                sentimentSamples.pollFirst();
            } else {
                break;
            }
        }
        while (sentimentSamples.size() > 2000) {
            sentimentSamples.pollFirst();
        }
    }

    private BigDecimal decayedAverage() {
        trimWindow();
        final Instant now = Instant.now();
        if (sentimentSamples.isEmpty()) return null;
        double wSum = 0.0, vSum = 0.0;
        final double hl = Math.max(1, halfLifeMinutes);
        for (Iterator<SentSample> it = sentimentSamples.descendingIterator(); it.hasNext(); ) {
            SentSample s = it.next();
            long ageMin = Math.max(0, Duration.between(s.ts, now).toMinutes());
            double w = Math.pow(0.5, ageMin / hl);
            double v = s.score.doubleValue();
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            wSum += w;
            vSum += w * v;
        }
        if (wSum <= 1e-9) return null;
        return BigDecimal.valueOf(vSum / wSum);
    }

    private record SentSample(Instant ts, BigDecimal score) {
    }

    // --- Real-time refresh and DB snapshot persistence ---
    @Scheduled(fixedDelayString = "${trade.sentiment.refresh-ms:60000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh() {
        try {
            BigDecimal score = computeScoreNow();
            if (score == null) return;
            recordSentimentSample(score);

            MarketSentimentSnapshot snap = new MarketSentimentSnapshot();
            snap.setAsOf(Instant.now());
            snap.setScore(score.setScale(0, RoundingMode.HALF_UP).intValue());

            // Persist to MongoDB
            try {
                sentimentRepo.save(snap);
            } catch (Exception t) {
                log.error("refresh(): repo save failed: {}", t);
            }

            // --- Publish to StreamGateway ("sentiment.update") ---
            try {
                stream.send("sentiment.update", snap);
                log.info("Sentiment snapshot published to StreamGateway: {}", snap.getScore());
            } catch (Exception e) {
                log.error("Failed to publish sentiment.update to stream: {}", e.getMessage());
            }

            // --- Publish Event to Kafka (via EventPublisher) ---
            try {
                if (bus != null) {
                    // Use a standard topic
                    String topic = EventBusConfig.TOPIC_SENTIMENT;
                    // Compose JSON payload
                    JsonObject event = new JsonObject();
                    event.addProperty("ts", snap.getAsOf().toEpochMilli());
                    event.addProperty("score", snap.getScore());
                    bus.publish(topic, "sentiment", event.toString());
                    log.info("Sentiment event published to Kafka topic {}: {}", topic, event);
                }
            } catch (Throwable ignore) {
                log.error("Sentiment Kafka publish failed");
            }

            // --- Audit log for compliance ---
            try {
                JsonObject auditPayload = new JsonObject();
                auditPayload.addProperty("asOf", snap.getAsOf().toString());
                auditPayload.addProperty("score", snap.getScore());
                audit("sentiment.update", auditPayload);
            } catch (Throwable ignore) {
            }

        } catch (Exception t) {
            log.error("refresh() failed: {}", t);
        }
    }

    // Kafkaesque audit helper (optional). Emits to TOPIC_AUDIT.
    private void audit(String event, JsonObject data) {
        try {
            if (bus == null) return;
            Instant now = Instant.now();
            JsonObject o = new JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", event);
            o.addProperty("source", "sentiment");
            if (data != null) o.add("data", data);
            bus.publish(EventBusConfig.TOPIC_AUDIT, "sentiment", o.toString());
        } catch (Throwable ignore) { /* best-effort */ }
    }


    // --- Dynamic Position Sizing Example (in INR) ---
    public BigDecimal getOptimalPositionSize(String symbol, int days, BigDecimal maxRiskPct, BigDecimal portfolioValueINR) {
        BigDecimal sentiment = getMarketSentimentScore().orElse(BigDecimal.valueOf(50));
        BigDecimal volatility = marketDataService.getCurrentVolatility(symbol, days).orElse(BigDecimal.valueOf(2.0));
        return positionSizer.size(sentiment, volatility, maxRiskPct);
    }
}
