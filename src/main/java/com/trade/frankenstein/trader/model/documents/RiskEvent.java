package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Audit each block/throttle or notable risk event
 */
@Document("risk_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEvent {

    private @Id String id;

    private Instant ts;
    private String type;          // e.g., "DAILY_LOSS_BREACH", "LOTS_CAP"
    private String reason;
    private String orderRef;      // optional link to order/advice
    private double value;         // observed metric
    private boolean breached;     // true if a rule was breached
}
