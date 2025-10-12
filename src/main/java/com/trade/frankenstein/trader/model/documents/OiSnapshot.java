package com.trade.frankenstein.trader.model.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

@Document("oi_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OiSnapshot {
    @Id
    private String id;

    @Indexed
    private String underlyingKey;

    @Indexed
    private LocalDate expiry;

    @Indexed
    private Instant timestamp;

    // Strike-wise OI data
    private Map<String, Long> callOiByStrike; // strike -> Call OI
    private Map<String, Long> putOiByStrike;  // strike -> Put OI

    // Strike-wise Volume data
    private Map<String, Long> callVolumeByStrike; // strike -> Call Volume
    private Map<String, Long> putVolumeByStrike;  // strike -> Put Volume

    // OI Changes (compared to previous snapshot)
    private Map<String, Long> callOiChanges; // strike -> Call OI Change
    private Map<String, Long> putOiChanges;  // strike -> Put OI Change

    // Aggregated Metrics
    private Long totalCallOi;
    private Long totalPutOi;
    private Long totalCallVolume;
    private Long totalPutVolume;

    // PCR Metrics
    private BigDecimal oiPcr; // Put/Call OI Ratio
    private BigDecimal volumePcr; // Put/Call Volume Ratio

    // Max Pain Calculation
    private BigDecimal maxPainStrike;
    private BigDecimal maxPainValue;

    // OI Distribution Analysis
    private BigDecimal oiConcentration; // Herfindahl index of OI distribution
    private String dominantStrike; // Strike with highest total OI
    private BigDecimal oiSkew; // Skewness of OI distribution

    // Support/Resistance Levels
    private Map<String, BigDecimal> supportLevels; // strike -> support strength
    private Map<String, BigDecimal> resistanceLevels; // strike -> resistance strength

    // Flow Analysis
    private Map<String, String> oiFlowDirection; // strike -> "BULLISH"/"BEARISH"/"NEUTRAL"
    private BigDecimal netCallFlow; // Net call buying/selling
    private BigDecimal netPutFlow; // Net put buying/selling

    // Institutional vs Retail Analysis
    private Map<String, Long> institutionalOi; // Estimated institutional OI by strike
    private Map<String, Long> retailOi; // Estimated retail OI by strike
    private BigDecimal institutionalRatio;

    // Time Decay Impact
    private Map<String, BigDecimal> timeDecayImpact; // strike -> theta impact
    private BigDecimal netTimeDecay;

    // Volatility Impact
    private Map<String, BigDecimal> vegaExposure; // strike -> vega exposure
    private BigDecimal netVegaExposure;

    // Delta Hedging Requirements
    private Map<String, BigDecimal> deltaHedgeRequirement; // strike -> delta hedge needed
    private BigDecimal netDeltaHedge;

    // Quality Metrics
    private Integer activeStrikes;
    private BigDecimal dataCompleteness; // % of expected strikes with data
    private Instant lastUpdate;

    // Previous snapshot reference for change calculation
    private String previousSnapshotId;
    private Instant previousSnapshotTime;

    // Market Context
    private BigDecimal underlyingPrice;
    private BigDecimal atmIv; // At-the-money implied volatility
    private String marketSession; // PRE_MARKET, REGULAR, POST_MARKET

    public BigDecimal getTotalOi() {
        Long callOi = totalCallOi != null ? totalCallOi : 0L;
        Long putOi = totalPutOi != null ? totalPutOi : 0L;
        return new BigDecimal(callOi + putOi);
    }

    public BigDecimal getTotalVolume() {
        Long callVol = totalCallVolume != null ? totalCallVolume : 0L;
        Long putVol = totalPutVolume != null ? totalPutVolume : 0L;
        return new BigDecimal(callVol + putVol);
    }

    public boolean hasSignificantActivity() {
        return getTotalVolume().compareTo(new BigDecimal("10000")) > 0;
    }

    public BigDecimal getOiImbalance() {
        if (totalCallOi == null || totalPutOi == null) return BigDecimal.ZERO;
        long total = totalCallOi + totalPutOi;
        if (total == 0) return BigDecimal.ZERO;

        return new BigDecimal(totalCallOi - totalPutOi)
                .divide(new BigDecimal(total), 4, java.math.RoundingMode.HALF_UP);
    }

    public boolean isCallHeavy() {
        return getOiImbalance().compareTo(new BigDecimal("0.1")) > 0;
    }

    public boolean isPutHeavy() {
        return getOiImbalance().compareTo(new BigDecimal("-0.1")) < 0;
    }
}
