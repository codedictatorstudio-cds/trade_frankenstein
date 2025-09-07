package com.trade.frankenstein.trader.model;

import com.trade.frankenstein.trader.enums.TradeMode;
import com.trade.frankenstein.trader.enums.Status;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AppHeaderModel {


    // --- Backend-provided fields ---
    private Status mode;                    // LIVE or SANDBOX

    private String brokerName;            // e.g., "Upstox"

    private TradeMode brokerStatus;       // OK / WARN / ERROR

    private String brokerStatusText;      // short label: "OK", "Token expiring", "Disconnected"

    private Instant lastTickTs;           // nullable; last tick time for the tracked symbol

    // --- UI-only (preference) ---
    private boolean showLastTick;         // whether to render the Last Tick badge

    // Getters/setters …
}
