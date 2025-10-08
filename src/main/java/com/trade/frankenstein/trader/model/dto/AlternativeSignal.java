package com.trade.frankenstein.trader.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AlternativeSignal {
    private String source;
    private double value;
    private String description;
}
