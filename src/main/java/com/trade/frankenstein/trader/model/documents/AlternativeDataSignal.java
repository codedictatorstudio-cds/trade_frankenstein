package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.SignalType;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Document
@Data
public class AlternativeDataSignal {

    @Id
    private String id;
    private SignalType type;
    private BigDecimal strength; // -1.0 to 1.0
    private String source;
    private Map<String, Object> metadata;
    private BigDecimal reliability;
    private Instant timestamp;
    private Duration validityPeriod;
}
