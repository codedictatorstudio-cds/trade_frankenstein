package com.trade.frankenstein.trader.service.options;

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

    private static final int CACHE_SECONDS = 30;

    @Override
    public OptionChainAnalyticsDTO calculateAnalytics(String underlyingKey, LocalDate expiry)

    @Override
    public VolatilitySurface buildVolatilitySurface(String underlyingKey, LocalDate expiry) {
        List<OptionInstrument> instr = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry);
        if (instr.isEmpty()) return null;

        List<String> keys = instr.stream()
                .map(OptionInstrument::getInstrumentKey)
                .collect(Collectors.toList());
        Map<String, MarketQuoteOptionGreekV3> greeks = greeksCalculationService.calculateGreeksBulk(keys);

        Map<BigDecimal, BigDecimal> calls = new TreeMap<>();
        Map<BigDecimal, BigDecimal> puts = new TreeMap<>();
        for (OptionInstrument oi : instr) {
            MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
            if (g == null || g.getIv() == null) continue;
            BigDecimal iv = BigDecimal.valueOf(g.getIv());
            BigDecimal strike = new BigDecimal(oi.getStrike());
            if ("CE".equals(oi.getOptionType())) calls.put(strike, iv);
            else if ("PE".equals(oi.getOptionType())) puts.put(strike, iv);
        }

        BigDecimal atm = Stream.concat(calls.values().stream(), puts.values().stream())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(calls.size() + puts.size()), 4, RoundingMode.HALF_UP);

        VolatilitySurface vs = VolatilitySurface.builder()
                .underlyingKey(underlyingKey)
                .expiry(expiry)
                .timestamp(Instant.now())
                .callVolatilities(calls)
                .putVolatilities(puts)
                .atmVolatility(atm)
                .build();
        return volatilitySurfaceRepo.save(vs);
    }

    @Override
    public BigDecimal calculateMaxPain(String underlyingKey, LocalDate expiry) {
        List<OptionInstrument> inst = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry);
        return computeMaxPain(inst);
    }

    @Override
    public BigDecimal calculateGammaExposure(String underlyingKey, LocalDate expiry) {
        List<OptionInstrument> inst = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry);
        Map<String, MarketQuoteOptionGreekV3> greeks = greeksCalculationService
                .calculateGreeksBulk(inst.stream()
                        .map(OptionInstrument::getInstrumentKey)
                        .collect(Collectors.toList()));
        return computeGammaExposure(inst, greeks);
    }

    @Override
    public List<OptionChainAnalyticsDTO.OiChangeDTO> getTopOiChanges(String underlyingKey, LocalDate expiry, int limit) {
        List<OptionInstrument> inst = optionInstrumentRepo
                .findByUnderlyingKeyAndExpiry(underlyingKey, expiry);
        return inst.stream()
                .filter(i -> i.getOi() != null && i.getOi() > 0)
                .sorted(Comparator.comparingLong(OptionInstrument::getOi).reversed())
                .limit(limit)
                .map(i -> OptionChainAnalyticsDTO.OiChangeDTO.builder()
                        .strike(new BigDecimal(i.getStrike()))
                        .optionType(i.getOptionType())
                        .currentOi(i.getOi())
                        .oiChange(i.getOi()) // placeholder
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public OptionChainAnalytics saveAnalyticsSnapshot(OptionChainAnalyticsDTO dto) {
        try {
            OptionChainAnalytics entity = OptionChainAnalytics.builder()
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
            return analyticsRepo.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save analytics snapshot", e);
            return null;
        }
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

    // ─── Helpers ───────────────────────────────────────────

    private OptionChainAnalyticsDTO buildDTO(String key, LocalDate expiry,
                                             List<OptionInstrument> inst, Map<String, MarketQuoteOptionGreekV3> greeks) {

        BigDecimal maxPain = computeMaxPain(inst);
        BigDecimal oiPcr = computeOiPcr(inst);
        BigDecimal volumePcr = computeVolumePcr(inst);
        BigDecimal ivSkew = computeIvSkew(inst, greeks);
        BigDecimal gammaExp = computeGammaExposure(inst, greeks);
        BigDecimal deltaNeutral = computeDeltaNeutral(inst, greeks);

        return OptionChainAnalyticsDTO.builder()
                .underlyingKey(key)
                .expiry(expiry)
                .calculatedAt(Instant.now())
                .maxPain(maxPain)
                .oiPcr(oiPcr)
                .volumePcr(volumePcr)
                .ivSkew(ivSkew)
                .gammaExposure(gammaExp)
                .deltaNeutralLevel(deltaNeutral)
                .topOiIncreases(getTopOiChanges(key, expiry, 5))
                .build();
    }

    private boolean isStale(Instant t) {
        return t == null || t.isBefore(Instant.now().minus(CACHE_SECONDS, ChronoUnit.SECONDS));
    }

    private OptionChainAnalyticsDTO emptyDTO(String key, LocalDate expiry) {
        return OptionChainAnalyticsDTO.builder()
                .underlyingKey(key)
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

    private BigDecimal computeMaxPain(List<OptionInstrument> inst) {
        Map<BigDecimal, BigDecimal> painMap = new HashMap<>();
        Set<BigDecimal> strikes = inst.stream()
                .map(i -> new BigDecimal(i.getStrike()))
                .collect(Collectors.toSet());

        for (BigDecimal test : strikes) {
            BigDecimal pain = BigDecimal.ZERO;
            for (OptionInstrument i : inst) {
                BigDecimal s = new BigDecimal(i.getStrike());
                Long oi = i.getOi() == null ? 0L : i.getOi();
                BigDecimal bdOi = new BigDecimal(oi);
                if ("CE".equals(i.getOptionType()) && test.compareTo(s) > 0) {
                    pain = pain.add(bdOi.multiply(test.subtract(s)));
                } else if ("PE".equals(i.getOptionType()) && s.compareTo(test) > 0) {
                    pain = pain.add(bdOi.multiply(s.subtract(test)));
                }
            }
            painMap.put(test, pain);
        }
        return painMap.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(BigDecimal.ZERO);
    }

    private BigDecimal computeOiPcr(List<OptionInstrument> inst) {
        long ce = 0, pe = 0;
        for (var i : inst) {
            Long oi = i.getOi() == null ? 0L : i.getOi();
            if ("CE".equals(i.getOptionType())) ce += oi;
            else if ("PE".equals(i.getOptionType())) pe += oi;
        }
        return ce == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(pe).divide(BigDecimal.valueOf(ce), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeVolumePcr(List<OptionInstrument> inst) {
        long ce = 0, pe = 0;
        for (var i : inst) {
            Long v = i.getVolume() == null ? 0L : i.getVolume();
            if ("CE".equals(i.getOptionType())) ce += v;
            else if ("PE".equals(i.getOptionType())) pe += v;
        }
        return ce == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(pe).divide(BigDecimal.valueOf(ce), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeIvSkew(List<OptionInstrument> inst, Map<String, MarketQuoteOptionGreekV3> g) {
        List<BigDecimal> ceI = new ArrayList<>(), peI = new ArrayList<>();
        for (var i : inst) {
            var mg = g.get(i.getInstrumentKey());
            if (mg == null || mg.getIv() == null) continue;
            BigDecimal iv = BigDecimal.valueOf(mg.getIv());
            if ("CE".equals(i.getOptionType())) ceI.add(iv);
            else peI.add(iv);
        }
        if (ceI.isEmpty() || peI.isEmpty()) return BigDecimal.ZERO;
        BigDecimal ceMean = mean(ceI), peMean = mean(peI);
        return peMean.subtract(ceMean).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeGammaExposure(List<OptionInstrument> inst, Map<String, MarketQuoteOptionGreekV3> g) {
        BigDecimal total = BigDecimal.ZERO;
        for (var i : inst) {
            var mg = g.get(i.getInstrumentKey());
            if (mg == null || mg.getGamma() == null) continue;
            BigDecimal gamma = BigDecimal.valueOf(mg.getGamma());
            Long oi = i.getOi() == null ? 0L : i.getOi();
            BigDecimal expo = gamma.multiply(new BigDecimal(oi));
            total = "CE".equals(i.getOptionType())
                    ? total.add(expo)
                    : total.subtract(expo);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeDeltaNeutral(List<OptionInstrument> inst, Map<String, MarketQuoteOptionGreekV3> g) {
        BigDecimal net = BigDecimal.ZERO;
        for (var i : inst) {
            var mg = g.get(i.getInstrumentKey());
            if (mg == null || mg.getDelta() == null) continue;
            BigDecimal d = BigDecimal.valueOf(mg.getDelta());
            Long oi = i.getOi() == null ? 0L : i.getOi();
            net = "CE".equals(i.getOptionType())
                    ? net.add(d.multiply(new BigDecimal(oi)))
                    : net.subtract(d.multiply(new BigDecimal(oi)));
        }
        return net.setScale(2, RoundingMode.HALF_UP);
    }

    private List<List<String>> partitionList(List<String> list, int size) {
        List<List<String>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    private BigDecimal mean(List<BigDecimal> v) {
        if (v.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = v.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(v.size()), 4, RoundingMode.HALF_UP);
    }
}
