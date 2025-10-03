package com.trade.frankenstein.trader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Prediction of short-term price direction.
 */
@Data
@AllArgsConstructor
public class DirectionPrediction {

    private Direction direction;      // UP, DOWN, FLAT
    private double confidence;        // 0..1
    private Instant asOf;             // timestamp of prediction

    public enum Direction {
        UP, DOWN, FLAT
    }
}
