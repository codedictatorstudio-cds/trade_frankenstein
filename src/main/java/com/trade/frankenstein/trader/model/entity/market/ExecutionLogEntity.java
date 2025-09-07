package com.trade.frankenstein.trader.model.entity.market;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "execution_logs",
        indexes = {
                @Index(name = "ix_exec_corr_ts", columnList = "correlationId, ts"),
                @Index(name = "ix_exec_stage", columnList = "stage"),
                @Index(name = "ix_exec_ts", columnList = "ts DESC")
        })
public class ExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Correlation/request id (propagate from RequestCorrelationFilter)
     */
    @Column(length = 64)
    private String correlationId;

    /**
     * UTC timestamp
     */
    @Column(nullable = false)
    private Instant ts;

    /**
     * Stage labels: DECISION, RISK_CHECK, ORDER_SUBMIT, ORDER_UPDATE, TRADE_FILL, etc.
     */
    @Column(nullable = false, length = 32)
    private String stage;

    /**
     * Message summary
     */
    @Column(nullable = false, length = 512)
    private String message;

    /**
     * Optional structured payload (JSON string)
     */
    @Lob
    private String payload;
}
