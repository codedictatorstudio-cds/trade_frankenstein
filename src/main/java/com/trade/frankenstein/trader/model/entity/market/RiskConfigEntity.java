package com.trade.frankenstein.trader.model.entity.market;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "risk_config")
public class RiskConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Daily max loss in INR before circuit breaker trips
     */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLossCap;

    /**
     * Max lots total intraday
     */
    @Column(nullable = false)
    private Integer lotsCap;

    /**
     * Orders/minute throttle cap
     */
    @Column(nullable = false)
    private Integer ordersPerMinuteCap;

    /**
     * Per-order guards
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal perOrderMaxValue;

    @Column
    private Integer perOrderMaxLots;

    /**
     * Max acceptable slippage in % (0..100)
     */
    @Column(precision = 7, scale = 4)
    private BigDecimal maxSlippagePct;

    /**
     * If true, enable automatic circuit breaker when caps are breached
     */
    @Column(nullable = false)
    private boolean circuitBreakerAuto;

    @Column(nullable = false)
    private Instant updatedAt;
}
