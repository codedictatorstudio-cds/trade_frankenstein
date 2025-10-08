package com.trade.frankenstein.trader.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Options order-flow based directional bias.
 */
@Data
@AllArgsConstructor
public class OptionsFlowBias {

    private double callVolumeRatio;   // CE volume / total
    private double putVolumeRatio;    // PE volume / total
    private double netOiChange;       // CE ΔOI – PE ΔOI
    private Instant asOf;             // timestamp of snapshot
}
