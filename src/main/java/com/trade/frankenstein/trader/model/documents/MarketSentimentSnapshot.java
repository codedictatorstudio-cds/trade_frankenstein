package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("market_sentiment_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketSentimentSnapshot {

    @Id
    private String id;

    private Instant asOf;
    private String sentiment;          // Bullish/Bearish/Neutral
    private Integer score;             // 0..100
    private Integer confidence;        // 0..100
    private Integer predictionAccuracy;// 0..100
    private String emoji;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
