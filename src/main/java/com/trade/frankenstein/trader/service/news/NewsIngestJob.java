package com.trade.frankenstein.trader.service.news;

import com.trade.frankenstein.trader.service.sentiment.SentimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class NewsIngestJob {

    private final NewsService newsService;
    private final SentimentService sentimentService; // inject your existing service with getNow()

    @Value("${trade.news.enabled:true}")
    private boolean newsEnabled;

    @Scheduled(fixedDelayString = "${trade.news.refresh-ms:30000}")
    public void schedule() {
        if (!newsEnabled) return;
        newsService.ingestAndUpdateSentiment();
        // Reuse your existing streaming pathway
        try {
            sentimentService.getNow(); // will emit "sentiment.update" with the latest DTO
        } catch (Throwable t) {
            log.warn("sentiment.getNow() emit failed: {}", t.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${trade.news.refresh-ms:30000}")
    public void run() {
        if (!isMarketHoursNowIst()) return;
        try {
            newsService.ingestAndUpdateSentiment();
        } catch (Throwable t) {
            log.warn("News ingest failed", t);
        }
    }

    private static boolean isMarketHoursNowIst() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }
}
