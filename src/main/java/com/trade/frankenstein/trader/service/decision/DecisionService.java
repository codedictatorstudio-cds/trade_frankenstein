package com.trade.frankenstein.trader.service.decision;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.InstrumentType;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.model.entity.AdviceEntity;
import com.trade.frankenstein.trader.model.entity.InstrumentEntity;
import com.trade.frankenstein.trader.repo.AdviceRepository;
import com.trade.frankenstein.trader.repo.InstrumentRepository;
import com.trade.frankenstein.trader.repo.MarketRegimeSnapshotRepository;
import com.trade.frankenstein.trader.repo.MarketSentimentSnapshotRepository;
import com.trade.frankenstein.trader.service.risk.RiskService;
import com.trade.frankenstein.trader.service.sentiment.SentimentService;
import com.trade.frankenstein.trader.service.signals.MarketSignalsService;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    private final MarketRegimeSnapshotRepository regimeRepo;
    private final MarketSentimentSnapshotRepository sentimentRepo;
    private final AdviceRepository adviceRepo;
    private final RiskService riskService;
    private final StreamGateway streamGateway;
    private final InstrumentRepository instrumentRepository;
    private final SentimentService sentimentService;
    private final MarketSignalsService marketSignalsService; // new service we added

    @Value("${trade.strategy.mode:SENTIMENT_ONLY}")
    private String strategyMode; // SENTIMENT_ONLY or DQS
    @Value("${trade.strategy.sentiment.min-confidence:65}")
    private int minConf;
    @Value("${trade.strategy.sentiment.entry-long-score:60}")
    private int longScore;
    @Value("${trade.strategy.sentiment.entry-short-score:40}")
    private int shortScore;
    @Value("${trade.strategy.sentiment.cooldown-minutes:5}")
    private int cooldownMin;
    @Value("${trade.symbol.nifty:NIFTY}")
    private String niftySymbol;


    // Score gates (typed, configurable)
    @Value("${trade.advice.threshold.entry:0.35}")
    private double entryThreshold;       // abs score needed to create an advice

    private static final DateTimeFormatter HHMMSS_IST = DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("Asia/Kolkata"));

    /**
     * Existing card endpoint – unchanged here.
     */

    public Result<DecisionQualityDto> getQuality() {
        try {
            // sentiment
            SentimentService.SentimentNowDto s = sentimentService.getNow().orElseGet(() ->
                    new SentimentService.SentimentNowDto("—", 50, 50, java.math.BigDecimal.ZERO, Instant.now())
            );

            // momentum/regime from signals (use your own normalization)
            var regimeSnap = marketSignalsService.refreshRegime().getData();
            int mScore = (regimeSnap == null) ? 50 : normalizeRegimeScore(regimeSnap.getRegime());

            int sScore = clamp0to100(s.sentimentScore());
            int score = (int) Math.round(0.6 * sScore + 0.4 * mScore);

            String trend = (regimeSnap == null) ? "Neutral" : regimeSnap.getRegime().name();

            DecisionQualityDto dto = new DecisionQualityDto(
                    score,
                    trend,
                    Arrays.asList("Sentiment=" + sScore, "Momentum=" + mScore, "Regime=" + trend),
                    Arrays.asList("RR:OK", "Slippage:LOW", "Throttle:NORMAL"),
                    Instant.now()
            );

            try {
                streamGateway.send("decision.quality", dto);
            } catch (Throwable t) {
                log.info("stream send failed: {}", t.getMessage());
            }
            return Result.ok(dto);
        } catch (Throwable t) {
            log.error("getQuality failed", t);
            return Result.fail(t);
        }
    }

    // ---- small helpers ----
    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int normalizeRegimeScore(MarketRegime r) {
        if (r == null) return 50;
        return switch (r) {
            case BULLISH -> 70;
            case BEARISH -> 30;
            case RANGE_BOUND -> 50;
            case HIGH_VOLATILITY -> 45;
            case LOW_VOLATILITY -> 55;
            default -> 50;
        };
    }

    /**
     * @param score 0..100
     * @param trend Uptrend / Downtrend / Range
     * @param tags  RR/Slippage/Throttle
     */
    public record DecisionQualityDto(int score, String trend, List<String> reasons, List<String> tags, Instant asOf) {
    }


    /**
     * Generate at most one PENDING advice using only regime & sentiment (typed).
     * Repos are accessed via findAll(PageRequest) – no custom methods required.
     */
    @Transactional
    public Result<AdviceEntity> generateAdvice() {
        var cb = riskService.getCircuitState();
        if (cb.isOk() && cb.get().tripped()) return Result.fail("CIRCUIT_TRIPPED");

        var sent = sentimentRepo.findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);
        if (sent == null) return Result.fail("NO_SENTIMENT");

        int score = clamp(sent.getSentimentScore(), 0, 100);
        int conf = clamp(sent.getConfidence(), 0, 100);

        String optType = null;
        if ("SENTIMENT_ONLY".equalsIgnoreCase(strategyMode)) {
            if (conf < minConf) return Result.fail("CONF_LOW");
            if (score >= longScore) optType = "CE";
            else if (score <= shortScore) optType = "PE";
            else return Result.fail("SENTIMENT_INCONCLUSIVE");
        } else { // DQS
            var regimeSnap = regimeRepo.findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                    .stream().findFirst().orElse(null);
            double R = regimeSnap == null ? 0.0 : (regimeSnap.getRegime() == MarketRegime.BULLISH ? +1 : regimeSnap.getRegime() == MarketRegime.BEARISH ? -1 : 0)
                    * Math.max(0.0, Math.min(1.0, regimeSnap.getStrength()));
            double M = marketSignalsService.computeMomentumZScore().map(BigDecimal::doubleValue).get();
            M = Math.max(-1.0, Math.min(1.0, M));
            double S = ((score - 50.0) / 50.0) * (conf / 100.0);
            double raw = 0.40 * R + 0.35 * M + 0.25 * S;
            if (Math.abs(raw) < 0.35) return Result.fail("SCORE_LOW");
            optType = raw >= 0 ? "CE" : "PE";
        }

        InstrumentEntity inst = ensureInstrument(niftySymbol, "CE".equals(optType));

        AdviceEntity a = new AdviceEntity();
        a.setCreatedAt(Instant.now());
        a.setInstrument(inst);
        a.setSide(OrderSide.BUY);
        a.setConfidence(conf);
        a.setTechScore("SENTIMENT_ONLY".equalsIgnoreCase(strategyMode) ? 0 : (int) Math.round(100 * 0.75)); // or compute properly
        a.setNewsScore(conf);
        a.setStatus(AdviceStatus.PENDING);
        a.setReason("Mode=" + strategyMode + ", score=" + score + ", conf=" + conf + ", opt=" + optType);

        AdviceEntity saved = adviceRepo.save(a);
        streamGateway.send("advice.new", saved);
        return Result.ok(saved);
    }

    private int clamp(int sentimentScore, int i, int i1) {
        return Math.max(i, Math.min(i1, sentimentScore));
    }

    private InstrumentEntity ensureInstrument(String base, boolean bullish) {
        String sym = base + " " + (bullish ? "CE" : "PE"); // "NIFTY CE"/"NIFTY PE"
        return instrumentRepository.findAll(PageRequest.of(0, 500)).stream()
                .filter(i -> sym.equalsIgnoreCase(i.getSymbol())).findFirst()
                .orElseGet(() -> {
                    InstrumentEntity e = new InstrumentEntity();
                    e.setSymbol(sym);
                    e.setType(InstrumentType.OPTION);
                    return instrumentRepository.save(e);
                });
    }


    // ----- helpers (typed-only) -----

    private static double regimeToSignal(MarketRegime r) {
        if (r == null) return 0.0;
        switch (r) {
            case BULLISH:
                return +1.0;
            case BEARISH:
                return -1.0;
            default:
                return 0.0;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private InstrumentEntity ensureAdviceInstrument(String baseSymbol, boolean bullish) {
        final String symbol = baseSymbol + " " + (bullish ? "CE" : "PE"); // e.g., "NIFTY CE" or "NIFTY PE"
        // Repos were created without custom methods, so use findAll(PageRequest) + in-memory filter
        InstrumentEntity existing = instrumentRepository
                .findAll(PageRequest.of(0, 500))
                .stream()
                .filter(i -> symbol.equalsIgnoreCase(i.getSymbol()))
                .findFirst()
                .orElse(null);
        if (existing != null) return existing;

        // Create a minimal, valid OPTION instrument and persist it
        InstrumentEntity created = new InstrumentEntity();
        created.setSymbol(symbol);
        created.setType(InstrumentType.OPTION);
        // If your InstrumentEntity includes optional fields (e.g., displayName), feel free to set them too.
        // If it has non-nullable fields (e.g., lotSize), set safe defaults consistent with your schema.

        return instrumentRepository.save(created);
    }
}
