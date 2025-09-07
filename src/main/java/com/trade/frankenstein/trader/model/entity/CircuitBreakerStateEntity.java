package com.trade.frankenstein.trader.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "circuit_breaker_state",
        indexes = {
                @Index(name = "ix_cb_state_asof", columnList = "asOf DESC"),
                @Index(name = "ix_cb_state_active", columnList = "active")
        })
public class CircuitBreakerStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Latest update time (UTC)
     */
    @Column(nullable = false)
    private Instant asOf;

    @Column(nullable = false)
    private boolean active;

    /**
     * Human-friendly explanation (shown in Risk panel/circuit SSE consumers if needed)
     */
    @Column(length = 512)
    private String reason;

    @Column
    private Instant triggeredAt;

    @Column
    private Instant resetAt;
}
