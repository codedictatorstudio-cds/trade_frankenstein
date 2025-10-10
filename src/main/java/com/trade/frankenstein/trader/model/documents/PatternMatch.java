package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.Direction;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Document
@Data
public class PatternMatch {

    @Id
    private String id;
    private String patternName;
    private BigDecimal confidence;
    private Direction expectedDirection;
    private BigDecimal targetPrice;
    private BigDecimal supportLevel;
    private BigDecimal resistanceLevel;
    private Duration expectedDuration;
    private Instant detectedAt;
    private String detectionMethod;
}

