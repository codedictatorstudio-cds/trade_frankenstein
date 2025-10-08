package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.dto.OptionChainData;
import com.trade.frankenstein.trader.dto.OptionsChainAnalysisResult;
import com.trade.frankenstein.trader.model.documents.OptionContract;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Service
public class PcrOptionAnalytics implements OptionAnalytics {

    @Override
    public OptionsChainAnalysisResult analyze(Object chainDataObj) {
        if (!(chainDataObj instanceof OptionChainData)) {
            throw new IllegalArgumentException("Expected OptionChainData object");
        }

        OptionChainData chainData = (OptionChainData) chainDataObj;
        Map<String, Object> result = new HashMap<>();

        try {
            // Calculate OI based PCR
            BigDecimal oiPcr = calculateOiPcr(chainData);
            result.put("oi_pcr", oiPcr);

            // Calculate Volume based PCR
            BigDecimal volumePcr = calculateVolumePcr(chainData);
            result.put("volume_pcr", volumePcr);

            // Calculate strike-wise PCR
            Map<String, BigDecimal> strikeWisePcr = calculateStrikeWisePcr(chainData);
            result.put("strike_wise_pcr", strikeWisePcr);

            result.put("analysis_type", getName());
            result.put("underlying", chainData.getUnderlying());
            result.put("expiry", chainData.getExpiry());

        } catch (Exception e) {
            result.put("error", "PCR calculation failed: " + e.getMessage());
        }

        return new OptionsChainAnalysisResult(getName(), result);
    }

    private BigDecimal calculateOiPcr(OptionChainData data) {
        BigDecimal totalPeOi = data.getPuts().stream()
                .filter(p -> p.getOpenInterest() != null)
                .map(OptionContract::getOpenInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCeOi = data.getCalls().stream()
                .filter(c -> c.getOpenInterest() != null)
                .map(OptionContract::getOpenInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCeOi.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return totalPeOi.divide(totalCeOi, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateVolumePcr(OptionChainData data) {
        BigDecimal totalPeVolume = data.getPuts().stream()
                .filter(p -> p.getVolume() != null)
                .map(OptionContract::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCeVolume = data.getCalls().stream()
                .filter(c -> c.getVolume() != null)
                .map(OptionContract::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCeVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return totalPeVolume.divide(totalCeVolume, 4, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> calculateStrikeWisePcr(OptionChainData data) {
        Map<String, BigDecimal> strikeWisePcr = new HashMap<>();
        // Implementation for strike-wise PCR calculation
        return strikeWisePcr;
    }

    @Override
    public String getName() {
        return "PCR";
    }

    @Override
    public double getVersion() {
        return 1.0;
    }
}
