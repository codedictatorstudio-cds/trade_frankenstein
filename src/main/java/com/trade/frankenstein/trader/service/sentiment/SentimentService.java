package com.trade.frankenstein.trader.service.sentiment;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.entity.MarketSentimentSnapshotEntity;
import com.trade.frankenstein.trader.repo.MarketSentimentSnapshotRepository;
import com.trade.frankenstein.trader.service.news.NewsService;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class SentimentService {

    private final MarketSentimentSnapshotRepository repo;
    private final StreamGateway stream;
    private final NewsService newsService;

    /**
     * Returns the latest market sentiment snapshot (typed) and broadcasts it
     * on SSE topic "sentiment.update".
     */
    @Transactional(readOnly = true)
    public Result<SentimentNowDto> getNow() {
        var snap = repo.findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);

        SentimentNowDto dto = (snap == null) ? neutralDto() : toDto(snap);

        try {
            stream.send("sentiment.update", dto);
        } catch (Throwable ignored) {
        }
        return Result.ok(dto);
    }

    // ---------------------------------------------------------------------
    // Mapping (typed)
    // ---------------------------------------------------------------------

    private SentimentNowDto toDto(MarketSentimentSnapshotEntity s) {
        // Entity fields are already non-null (primitives) except BigDecimal
        int score = clamp0to100(s.getSentimentScore());
        int confidence = clamp0to100(s.getConfidence());
        BigDecimal accuracy = nvl(s.getPredictionAccuracyPct(), BigDecimal.ZERO);
        Instant asOf = s.getAsOf() == null ? Instant.now() : s.getAsOf();

        return new SentimentNowDto(
                safe(s.getInstrumentSymbol()),
                score,
                confidence,
                accuracy,
                asOf
        );
    }

    private SentimentNowDto neutralDto() {
        return new SentimentNowDto("—", 50, 50, BigDecimal.ZERO, Instant.now());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static BigDecimal nvl(BigDecimal v, BigDecimal d) {
        return v == null ? d : v;
    }

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    // ---------------------------------------------------------------------
    // DTO (stable, UI-facing)
    // ---------------------------------------------------------------------

    /**
     * @param instrumentSymbol      e.g., "NIFTY"
     * @param sentimentScore        0..100
     * @param confidence            0..100
     * @param predictionAccuracyPct 0..100 (decimal percent)
     */
    public record SentimentNowDto(String instrumentSymbol, int sentimentScore, int confidence,
                                  BigDecimal predictionAccuracyPct, Instant asOf) {
    }
}
