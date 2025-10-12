package com.trade.frankenstein.trader.service.options;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.dto.OptionChainAnalyticsDTO;
import com.trade.frankenstein.trader.model.documents.OptionChainAnalytics;
import com.trade.frankenstein.trader.model.documents.OptionInstrument;
import com.trade.frankenstein.trader.model.documents.VolatilitySurface;
import com.trade.frankenstein.trader.repo.documents.OptionChainAnalyticsRepo;
import com.trade.frankenstein.trader.repo.documents.OptionInstrumentRepo;
import com.trade.frankenstein.trader.repo.documents.VolatilitySurfaceRepo;
import com.trade.frankenstein.trader.service.GreeksCalculationService;
import com.trade.frankenstein.trader.service.OptionChainAnalyticsService;
import com.upstox.api.MarketQuoteOptionGreekV3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class OptionChainAnalyticsServiceImpl implements OptionChainAnalyticsService {

    @Autowired
    private OptionInstrumentRepo optionInstrumentRepo;
    @Autowired
    private GreeksCalculationService greeksCalculationService;
    @Autowired
    private OptionChainAnalyticsRepo analyticsRepo;
    @Autowired
    private VolatilitySurfaceRepo volatilitySurfaceRepo;
    @Autowired
    private FastStateStore fastStateStore;
    @Autowired
    private ObjectMapper objectMapper;

    private static final int CACHE_SECONDS = 30;

    @Override
    public OptionChainAnalyticsDTO calculateAnalytics(String underlyingKey, LocalDate expiry) {
        String cacheKey = "analytics:" + underlyingKey + ":" + expiry;
        Optional<String> cachedJson = fastStateStore.get(cacheKey);
        if (cachedJson.isPresent()) {
            try {
                OptionChainAnalyticsDTO dto = objectMapper.readValue(cachedJson.get(), OptionChainAnalyticsDTO.class);
                if (dto.getCalculatedAt() != null &&
                        !dto.getCalculatedAt().isBefore(Instant.now().minusSeconds(CACHE_SECONDS))) {
                    return dto;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse cached analytics JSON", e);
            }
        }

        List<OptionInstrument> instruments = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry.toString());
        if (instruments.isEmpty()) {
            OptionChainAnalyticsDTO empty = createEmptyAnalytics(underlyingKey, expiry);
            cacheAnalytics(cacheKey, empty);
            return empty;
        }

        List<String> instrumentKeys = instruments.stream()
                .map(OptionInstrument::getInstrument_key)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<String, MarketQuoteOptionGreekV3> greeks =
                greeksCalculationService.calculateGreeksBulk(instrumentKeys);

        OptionChainAnalyticsDTO dto = OptionChainAnalyticsDTO.builder()
                .underlyingKey(underlyingKey)
                .expiry(expiry)
                .calculatedAt(Instant.now())
                .maxPain(computeMaxPain(instruments, greeks))
                .oiPcr(computeOiPcr(instruments, greeks))
                .volumePcr(computeVolumePcr(instruments, greeks))
                .ivSkew(computeIvSkew(instruments, greeks))
                .gammaExposure(computeGammaExposure(instruments, greeks))
                .deltaNeutralLevel(computeDeltaNeutralLevel(instruments, greeks))
                .topOiIncreases(getTopOiChanges(instruments, greeks, true, 5))
                .topOiDecreases(getTopOiChanges(instruments, greeks, false, 5))
                .greeksSummary(computeGreeksSummary(instruments, greeks))
                .volatilityMetrics(computeVolatilityMetrics(instruments, greeks))
                .liquidityMetrics(computeLiquidityMetrics(instruments, greeks))
                .build();

        cacheAnalytics(cacheKey, dto);
        analyticsRepo.save(toEntity(dto));
        return dto;
    }

    @Override
    public VolatilitySurface buildVolatilitySurface(String underlyingKey, LocalDate expiry) {
        List<OptionInstrument> instruments = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry.toString());
        if (instruments.isEmpty()) return null;

        List<String> instrumentKeys = instruments.stream()
                .map(OptionInstrument::getInstrument_key)
                .collect(Collectors.toList());
        Map<String, MarketQuoteOptionGreekV3> greeks =
                greeksCalculationService.calculateGreeksBulk(instrumentKeys);

        Map<BigDecimal, BigDecimal> callVols = new TreeMap<>();
        Map<BigDecimal, BigDecimal> putVols = new TreeMap<>();

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getIv() == null) continue;

            BigDecimal iv = BigDecimal.valueOf(g.getIv());
            BigDecimal strike = new BigDecimal(inst.getStrike_price());

            // Determine if CE or PE based on instrument_type
            if ("CE".equals(inst.getInstrument_type())) {
                callVols.put(strike, iv);
            } else if ("PE".equals(inst.getInstrument_type())) {
                putVols.put(strike, iv);
            }
        }

        if (callVols.isEmpty() && putVols.isEmpty()) return null;

        BigDecimal atm = Stream.concat(callVols.values().stream(), putVols.values().stream())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(callVols.size() + putVols.size()), 4, RoundingMode.HALF_UP);

        VolatilitySurface vs = VolatilitySurface.builder()
                .underlyingKey(underlyingKey)
                .expiry(expiry)
                .timestamp(Instant.now())
                .callVolatilities(callVols)
                .putVolatilities(putVols)
                .atmVolatility(atm)
                .build();

        return volatilitySurfaceRepo.save(vs);
    }

    @Override
    public BigDecimal calculateMaxPain(String underlyingKey, LocalDate expiry) {
        List<OptionInstrument> instruments = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry.toString());
        List<String> keys = instruments.stream()
                .map(OptionInstrument::getInstrument_key)
                .collect(Collectors.toList());
        Map<String, MarketQuoteOptionGreekV3> greeks =
                greeksCalculationService.calculateGreeksBulk(keys);
        return computeMaxPain(instruments, greeks);
    }

    @Override
    public BigDecimal calculateGammaExposure(String underlyingKey, LocalDate expiry) {
        List<OptionInstrument> instruments = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry.toString());
        List<String> keys = instruments.stream()
                .map(OptionInstrument::getInstrument_key)
                .collect(Collectors.toList());
        Map<String, MarketQuoteOptionGreekV3> greeks =
                greeksCalculationService.calculateGreeksBulk(keys);
        return computeGammaExposure(instruments, greeks);
    }

    @Override
    public List<OptionChainAnalyticsDTO.OiChangeDTO> getTopOiChanges(
            String underlyingKey, LocalDate expiry, int limit) {
        List<OptionInstrument> instruments = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry.toString());
        List<String> keys = instruments.stream()
                .map(OptionInstrument::getInstrument_key)
                .collect(Collectors.toList());
        Map<String, MarketQuoteOptionGreekV3> greeks =
                greeksCalculationService.calculateGreeksBulk(keys);
        return getTopOiChanges(instruments, greeks, true, limit);
    }

    @Override
    public OptionChainAnalytics saveAnalyticsSnapshot(OptionChainAnalyticsDTO dto) {
        return analyticsRepo.save(toEntity(dto));
    }

    @Override
    public List<OptionChainAnalytics> getHistoricalAnalytics(String underlyingKey, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return analyticsRepo.findHistoricalAnalytics(underlyingKey, since);
    }

    @Override
    public void cleanupOldAnalytics(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        analyticsRepo.deleteByCalculatedAtBefore(cutoff);
    }

    // Helper methods

    private void cacheAnalytics(String key, OptionChainAnalyticsDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            fastStateStore.put(key, json, Duration.ofSeconds(CACHE_SECONDS));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize analytics DTO for caching", e);
        }
    }

    private OptionChainAnalyticsDTO createEmptyAnalytics(String underlyingKey, LocalDate expiry) {
        return OptionChainAnalyticsDTO.builder()
                .underlyingKey(underlyingKey)
                .expiry(expiry)
                .calculatedAt(Instant.now())
                .maxPain(BigDecimal.ZERO)
                .oiPcr(BigDecimal.ZERO)
                .volumePcr(BigDecimal.ZERO)
                .ivSkew(BigDecimal.ZERO)
                .gammaExposure(BigDecimal.ZERO)
                .deltaNeutralLevel(BigDecimal.ZERO)
                .build();
    }

    private BigDecimal computeMaxPain(List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {
        Map<BigDecimal, BigDecimal> painMap = new HashMap<>();
        Set<BigDecimal> strikes = instruments.stream()
                .map(i -> new BigDecimal(i.getStrike_price()))
                .collect(Collectors.toSet());

        for (BigDecimal testStrike : strikes) {
            BigDecimal totalPain = BigDecimal.ZERO;
            for (OptionInstrument inst : instruments) {
                MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
                if (g == null || g.getOi() == null) continue;

                BigDecimal strike = new BigDecimal(inst.getStrike_price());
                BigDecimal oi = BigDecimal.valueOf(g.getOi());

                if ("CE".equals(inst.getInstrument_type()) && testStrike.compareTo(strike) > 0) {
                    totalPain = totalPain.add(oi.multiply(testStrike.subtract(strike)));
                } else if ("PE".equals(inst.getInstrument_type()) && strike.compareTo(testStrike) > 0) {
                    totalPain = totalPain.add(oi.multiply(strike.subtract(testStrike)));
                }
            }
            painMap.put(testStrike, totalPain);
        }

        return painMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal computeOiPcr(List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {
        BigDecimal ceOi = BigDecimal.ZERO;
        BigDecimal peOi = BigDecimal.ZERO;

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getOi() == null) continue;

            BigDecimal oi = BigDecimal.valueOf(g.getOi());
            if ("CE".equals(inst.getInstrument_type())) {
                ceOi = ceOi.add(oi);
            } else if ("PE".equals(inst.getInstrument_type())) {
                peOi = peOi.add(oi);
            }
        }

        return ceOi.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : peOi.divide(ceOi, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeVolumePcr(List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {
        BigDecimal ceVol = BigDecimal.ZERO;
        BigDecimal peVol = BigDecimal.ZERO;

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getVolume() == null) continue;

            BigDecimal vol = BigDecimal.valueOf(g.getVolume());
            if ("CE".equals(inst.getInstrument_type())) {
                ceVol = ceVol.add(vol);
            } else if ("PE".equals(inst.getInstrument_type())) {
                peVol = peVol.add(vol);
            }
        }

        return ceVol.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : peVol.divide(ceVol, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeIvSkew(List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {
        List<BigDecimal> ceIvs = new ArrayList<>();
        List<BigDecimal> peIvs = new ArrayList<>();

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getIv() == null) continue;

            BigDecimal iv = BigDecimal.valueOf(g.getIv());
            if ("CE".equals(inst.getInstrument_type())) {
                ceIvs.add(iv);
            } else if ("PE".equals(inst.getInstrument_type())) {
                peIvs.add(iv);
            }
        }

        if (ceIvs.isEmpty() || peIvs.isEmpty()) return BigDecimal.ZERO;
        return mean(peIvs).subtract(mean(ceIvs)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeGammaExposure(List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {
        BigDecimal totalExposure = BigDecimal.ZERO;

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getGamma() == null || g.getOi() == null) continue;

            BigDecimal gamma = BigDecimal.valueOf(g.getGamma());
            BigDecimal oi = BigDecimal.valueOf(g.getOi());
            BigDecimal exposure = gamma.multiply(oi);

            if ("CE".equals(inst.getInstrument_type())) {
                totalExposure = totalExposure.add(exposure);
            } else if ("PE".equals(inst.getInstrument_type())) {
                totalExposure = totalExposure.subtract(exposure);
            }
        }

        return totalExposure.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeDeltaNeutralLevel(List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {
        BigDecimal netDelta = BigDecimal.ZERO;

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getDelta() == null || g.getOi() == null) continue;

            BigDecimal delta = BigDecimal.valueOf(g.getDelta());
            BigDecimal oi = BigDecimal.valueOf(g.getOi());
            BigDecimal weightedDelta = delta.multiply(oi);

            if ("CE".equals(inst.getInstrument_type())) {
                netDelta = netDelta.add(weightedDelta);
            } else if ("PE".equals(inst.getInstrument_type())) {
                netDelta = netDelta.subtract(weightedDelta);
            }
        }

        return netDelta.setScale(2, RoundingMode.HALF_UP);
    }

    private List<OptionChainAnalyticsDTO.OiChangeDTO> getTopOiChanges(
            List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks,
            boolean isIncreasing, int limit) {

        return instruments.stream()
                .map(inst -> {
                    MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
                    if (g == null || g.getOi() == null || g.getOi() <= 0) return null;

                    return OptionChainAnalyticsDTO.OiChangeDTO.builder()
                            .strike(new BigDecimal(inst.getStrike_price()))
                            .optionType(inst.getInstrument_type())
                            .currentOi(g.getOi().longValue())
                            .oiChange(g.getOi().longValue()) // Simplified - should be actual change
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted((a, b) -> isIncreasing
                        ? Long.compare(b.getCurrentOi(), a.getCurrentOi())
                        : Long.compare(a.getCurrentOi(), b.getCurrentOi()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private OptionChainAnalyticsDTO.GreeksSummaryDTO computeGreeksSummary(
            List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {

        BigDecimal totalDelta = BigDecimal.ZERO;
        BigDecimal totalGamma = BigDecimal.ZERO;
        BigDecimal totalTheta = BigDecimal.ZERO;
        BigDecimal totalVega = BigDecimal.ZERO;

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getOi() == null) continue;

            BigDecimal oi = BigDecimal.valueOf(g.getOi());

            if (g.getDelta() != null) {
                totalDelta = totalDelta.add(BigDecimal.valueOf(g.getDelta()).multiply(oi));
            }
            if (g.getGamma() != null) {
                totalGamma = totalGamma.add(BigDecimal.valueOf(g.getGamma()).multiply(oi));
            }
            if (g.getTheta() != null) {
                totalTheta = totalTheta.add(BigDecimal.valueOf(g.getTheta()).multiply(oi));
            }
            if (g.getVega() != null) {
                totalVega = totalVega.add(BigDecimal.valueOf(g.getVega()).multiply(oi));
            }
        }

        return OptionChainAnalyticsDTO.GreeksSummaryDTO.builder()
                .totalDelta(totalDelta.setScale(2, RoundingMode.HALF_UP))
                .totalGamma(totalGamma.setScale(2, RoundingMode.HALF_UP))
                .totalTheta(totalTheta.setScale(2, RoundingMode.HALF_UP))
                .totalVega(totalVega.setScale(2, RoundingMode.HALF_UP))
                .netGammaExposure(computeGammaExposure(instruments, greeks))
                .build();
    }

    private OptionChainAnalyticsDTO.VolatilityMetricsDTO computeVolatilityMetrics(
            List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {

        List<BigDecimal> allIvs = new ArrayList<>();
        Map<String, BigDecimal> strikeIvMap = new HashMap<>();

        for (OptionInstrument inst : instruments) {
            MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
            if (g == null || g.getIv() == null) continue;

            BigDecimal iv = BigDecimal.valueOf(g.getIv());
            allIvs.add(iv);
            strikeIvMap.put(String.valueOf(inst.getStrike_price()), iv);
        }

        BigDecimal atmIv = allIvs.isEmpty() ? BigDecimal.ZERO : mean(allIvs);

        return OptionChainAnalyticsDTO.VolatilityMetricsDTO.builder()
                .atmIv(atmIv)
                .strikeIvMap(strikeIvMap)
                .build();
    }

    private OptionChainAnalyticsDTO.LiquidityMetricsDTO computeLiquidityMetrics(
            List<OptionInstrument> instruments, Map<String, MarketQuoteOptionGreekV3> greeks) {

        int activeStrikes = (int) instruments.stream()
                .filter(inst -> {
                    MarketQuoteOptionGreekV3 g = greeks.get(inst.getInstrument_key());
                    return g != null && g.getOi() != null && g.getOi() > 0;
                })
                .count();

        BigDecimal liquidityScore = activeStrikes > 10
                ? new BigDecimal("0.8")
                : new BigDecimal("0.3");

        return OptionChainAnalyticsDTO.LiquidityMetricsDTO.builder()
                .activeStrikes(activeStrikes)
                .liquidityScore(liquidityScore)
                .build();
    }

    private OptionChainAnalytics toEntity(OptionChainAnalyticsDTO dto) {
        return OptionChainAnalytics.builder()
                .underlyingKey(dto.getUnderlyingKey())
                .expiry(dto.getExpiry())
                .calculatedAt(dto.getCalculatedAt())
                .maxPain(dto.getMaxPain())
                .oiPcr(dto.getOiPcr())
                .volumePcr(dto.getVolumePcr())
                .ivSkew(dto.getIvSkew())
                .gammaExposure(dto.getGammaExposure())
                .deltaNeutralLevel(dto.getDeltaNeutralLevel())
                .build();
    }

    private BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(values.size()), 4, RoundingMode.HALF_UP);
    }
}
