package com.trade.frankenstein.trader.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public  class MarketSentimentModel {

    // --- Backend-provided ---
    private String  word;                   // "Bullish" | "Bearish" | "Neutral"
    private int     score;                  // 0..100
    private int     confidencePct;          // 0..100
    private int     predictionAccuracyPct;  // 0..100
    private Instant asOf;                   // snapshot timestamp

    // --- Optional (backend may omit; UI can decide) ---
    private String  emoji;                  // e.g., "😊" | "😐" | "😟"
}
