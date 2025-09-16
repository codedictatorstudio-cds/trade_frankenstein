package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.TradeNewsConstants;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic news ingest + sentiment update + cache/health maintenance.
 */
@Component
@Slf4j
public class NewsIngestJob {

    @Autowired
    private NewsService newsService;
    @Autowired
    private SentimentService sentimentService;

    private final AtomicLong lastSuccessfulIngest = new AtomicLong(0);
    private final AtomicLong lastSuccessfulEmit = new AtomicLong(0);
    private final AtomicLong totalIngests = new AtomicLong(0);
    private final AtomicLong successfulIngests = new AtomicLong(0);

    private boolean newsEnabled = TradeNewsConstants.NEWS_ENABLED;

    private boolean marketHoursOnly = TradeNewsConstants.NEWS_MARKET_HOURS_ONLY;

    private String marketTimezone = TradeNewsConstants.NEWS_MARKET_TIMEZONE;

    /**
     * Main news ingest job (defaults every 30s).
     */
    @Scheduled(fixedDelayString = TradeNewsConstants.NEWS_REFRESH_MS)
    public void ingestNewsAndUpdateSentiment() {
        if (!newsEnabled) return;
        if (marketHoursOnly && !isMarketHoursNow()) {
            log.debug("Skipping news ingest - outside market hours");
            return;
        }

        long start = System.currentTimeMillis();
        totalIngests.incrementAndGet();

        try {
            Result<MarketSentimentSnapshot> result = newsService.ingestAndUpdateSentiment();

            if (result.isOk()) {
                successfulIngests.incrementAndGet();
                lastSuccessfulIngest.set(System.currentTimeMillis());

                long elapsed = System.currentTimeMillis() - start;
                MarketSentimentSnapshot data = result.getData();
                log.info("News ingest ok in {}ms. Sentiment score={}, confidence={}",
                        Long.valueOf(elapsed),
                        (data == null ? null : data.getSentiment()),
                        (data == null ? null : data.getConfidence()));

                try {
                    // Trigger emission via SentimentService pathway (SSE)
                    Result<?> s = sentimentService.getNow();
                    if (s.isOk()) lastSuccessfulEmit.set(System.currentTimeMillis());
                } catch (Throwable t) {
                    log.warn("Failed to emit sentiment update: {}", t.getMessage());
                }
            } else {
                log.warn("News ingest failed: {}", result.getError());
            }
        } catch (Throwable t) {
            log.error("Error during news ingest job", t);
        }
    }

    /**
     * Periodically clear the news cache to ensure fresh data.
     */
    @Scheduled(cron = TradeNewsConstants.NEWS_CACHE_CLEAR_CRON)
    public void clearNewsCache() {
        if (!newsEnabled) return;
        try {
            newsService.clearCache();
            log.info("News cache cleared successfully");
        } catch (Exception e) {
            log.error("Failed to clear news cache", e);
        }
    }

    /**
     * Periodically re-validate feed health (light probe) during Indian market hours.
     * Default: every 10 minutes Mon–Fri 09:00–16:59 IST.
     */
    @Scheduled(cron = TradeNewsConstants.NEWS_HEALTH_CHECK_CRON)
    public void refreshFeedHealth() {
        if (!newsEnabled) return;
        if (marketHoursOnly && !isMarketHoursNow()) return;
        try {
            newsService.preflightValidateFeeds();
        } catch (Throwable t) {
            log.warn("Feed health refresh failed: {}", t.getMessage());
        }
    }

    // ----------------- Helpers / Status -----------------

    private boolean isMarketHoursNow() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(marketTimezone));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    public NewsIngestStatus getStatus() {
        NewsIngestStatus status = new NewsIngestStatus();
        status.setEnabled(newsEnabled);
        status.setTotalIngests(totalIngests.get());
        status.setSuccessfulIngests(successfulIngests.get());

        long lastIngest = lastSuccessfulIngest.get();
        if (lastIngest > 0) {
            status.setLastSuccessfulIngest(Instant.ofEpochMilli(lastIngest));
            status.setLastSuccessfulIngestFormatted(
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastIngest), ZoneId.systemDefault())
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            );
        }

        long lastEmit = lastSuccessfulEmit.get();
        if (lastEmit > 0) status.setLastSuccessfulEmit(Instant.ofEpochMilli(lastEmit));

        status.setMarketHoursOnly(marketHoursOnly);
        status.setCurrentlyInMarketHours(isMarketHoursNow());
        return status;
    }

    @Setter
    @Getter
    public static class NewsIngestStatus {
        private boolean enabled;
        private long totalIngests;
        private long successfulIngests;
        private Instant lastSuccessfulIngest;
        private String lastSuccessfulIngestFormatted;
        private Instant lastSuccessfulEmit;
        private boolean marketHoursOnly;
        private boolean currentlyInMarketHours;
    }
}
