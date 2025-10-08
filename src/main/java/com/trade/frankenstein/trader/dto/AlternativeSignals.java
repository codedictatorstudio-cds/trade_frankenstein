package com.trade.frankenstein.trader.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class AlternativeSignals {
    private double sentimentScore;
    private Map<String, Object> economicImpact;
    private double retailActivity;
    private double newsFlow;
}
