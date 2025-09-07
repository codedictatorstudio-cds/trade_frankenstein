package com.trade.frankenstein.trader.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public final class ExecutionAdvicesModel {

    // --- Backend-provided ---
    private List<Advice> items;
    private Instant asOf; // snapshot timestamp



}
