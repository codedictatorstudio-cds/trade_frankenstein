package com.trade.frankenstein.trader.model.dto;

import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.enums.VolatilityLevel;
import com.trade.frankenstein.trader.enums.VolumeProfile;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EnhancedMarketRegime {
    private MarketRegime primary;
    private MarketRegime shortTerm;
    private MarketRegime mediumTerm;
    private VolatilityLevel volatilityLevel;
    private VolumeProfile volumeProfile;
    private boolean newsImpact;
    private double regimeStrength;   // 0..1
    private double regimeConsistency;// 0..1

    public static EnhancedMarketRegime neutral() {
        return EnhancedMarketRegime.builder()
                .primary(MarketRegime.NEUTRAL)
                .shortTerm(MarketRegime.NEUTRAL)
                .mediumTerm(MarketRegime.NEUTRAL)
                .volatilityLevel(VolatilityLevel.MEDIUM)
                .volumeProfile(VolumeProfile.NORMAL)
                .regimeStrength(0.5)
                .regimeConsistency(0.5)
                .build();
    }
}
