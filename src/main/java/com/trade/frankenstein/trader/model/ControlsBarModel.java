package com.trade.frankenstein.trader.model;

import com.trade.frankenstein.trader.enums.EngineStatus;
import com.trade.frankenstein.trader.enums.Status;
import com.trade.frankenstein.trader.enums.TradeMode;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public final class ControlsBarModel {

    // --- Backend-provided ---
    private TradeMode mode;                       // Initial mode selection

    private EngineStatus engineStatus;       // Current engine state

    private boolean canStart;                // Allow Start

    private boolean canStop;                 // Allow Stop

    private boolean canKill;                 // Allow Kill (server-side policy)


    private int confidencePct;               // 0..100 for the meter

    private Status feedsStatus;              // Feeds: OK/WARN/ERR

    private String feedsNote;               // optional short note

    private Status ordersStatus;             // Orders: OK/WARN/ERR

    private String ordersNote;              // optional short note

    // Provide either expiry or ttlSeconds (one is enough; UI will format)
    private Instant tokenExpiry;             // Upstox access token expiry (optional)

    private Long tokenTtlSeconds;         // Remaining seconds (optional)
}
