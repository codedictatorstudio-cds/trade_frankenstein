package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.model.dto.DirectionPrediction;
import com.trade.frankenstein.trader.model.dto.VolatilityPrediction;
import com.trade.frankenstein.trader.service.market.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;

/**
 * Simple PredictionService implementation using momentum and volatility heuristics.
 */
@Service
public class PredictionServiceImpl implements PredictionService {

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private OptionChainService optionChainService;

    private final Random random = new Random();

    @Override
    public Optional<DirectionPrediction> predictDirection(String instrumentKey, int horizonMinutes) {
        try {
            // Use current momentum Z-score as proxy
            Optional<BigDecimal> zOpt = Optional.of(marketDataService.getMomentumNow(Instant.now()).get());
            if (zOpt.isEmpty()) return Optional.empty();

            double z = zOpt.get().doubleValue();
            DirectionPrediction.Direction dir;
            double conf;

            // Heuristic: strong positive z → UP, strong negative → DOWN, else FLAT
            if (z > 1.0) {
                dir = DirectionPrediction.Direction.UP;
                conf = Math.min(0.9, 0.5 + z / 4);
            } else if (z < -1.0) {
                dir = DirectionPrediction.Direction.DOWN;
                conf = Math.min(0.9, 0.5 + -z / 4);
            } else {
                dir = DirectionPrediction.Direction.FLAT;
                conf = Math.max(0.1, 0.5 - Math.abs(z) / 4);
            }

            // Slight randomness to model uncertainty
            conf = Math.max(0, Math.min(1, conf + (random.nextDouble() - 0.5) * 0.1));

            return Optional.of(new DirectionPrediction(dir, conf, Instant.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<VolatilityPrediction> predictVolatility(String instrumentKey, int horizonMinutes) {
        try {
            // Use current ATR% as proxy for volatility trend
            BigDecimal atrPct = BigDecimal.valueOf(marketDataService.getAtrJump5mPct(instrumentKey).get());
            if (atrPct == null) return Optional.empty();

            double pct = atrPct.doubleValue();
            double expectedChange;
            double conf;

            // Heuristic: ATR% rising indicates volatility expansion
            if (pct > 1.0) {
                expectedChange = 0.2 + (pct - 1.0) / 5;  // e.g. +20% baseline
                conf = Math.min(0.9, 0.5 + (pct - 1.0) / 4);
            } else {
                expectedChange = -0.1 - (1.0 - pct) / 10; // e.g. -10% baseline
                conf = Math.min(0.8, 0.5 + pct / 4);
            }

            // Add small noise
            expectedChange += (random.nextDouble() - 0.5) * 0.05;
            conf = Math.max(0, Math.min(1, conf + (random.nextDouble() - 0.5) * 0.1));

            return Optional.of(new VolatilityPrediction(expectedChange, conf, Instant.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
