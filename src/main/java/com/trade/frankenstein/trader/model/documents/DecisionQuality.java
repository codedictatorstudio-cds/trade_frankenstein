package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.MarketRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;


@Document("decision_quality")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionQuality {

    @Id
    private String id;

    private Instant asOf;
    private Integer score;          // 0..100
    private MarketRegime trend;     // BULLISH/BEARISH/NEUTRAL
    private List<String> reasons;   // bullets
    private String rr;              // e.g. "RR:1.8"
    private String slippage;        // e.g. "Low"
    private String throttle;        // e.g. "60%"
    private String accuracy;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;

    public DecisionQuality(int score, String trend, List<String> reasons, Map<String, String> tags, Instant now) {
        this.score = score;
        this.trend = MarketRegime.valueOf(trend);
        this.reasons = reasons;
        this.rr = tags.get("RR");
        this.slippage = tags.get("Slippage");
        this.throttle = tags.get("Throttle");
        this.accuracy = tags.get("Accuracy");
        this.asOf = now;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
