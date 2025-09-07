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
@Table(name = "broker_sessions",
        uniqueConstraints = @UniqueConstraint(name = "ux_broker_account",
                columnNames = {"broker", "accountId"}),
        indexes = {
                @Index(name = "ix_broker_expiry", columnList = "accessTokenExpiresAt")
        })
public class BrokerSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * e.g., "UPSTOX" (single broker for now)
     */
    @Column(nullable = false, length = 32)
    private String broker;

    /**
     * Optional account identifier from broker
     */
    @Column(length = 64)
    private String accountId;

    /**
     * Store encrypted/hashed tokens (DO NOT expose in DTOs)
     */
    @Column(length = 1024)
    private String accessTokenEnc;

    @Column(length = 1024)
    private String refreshTokenEnc;

    @Column
    private Instant accessTokenExpiresAt;

    @Column(length = 256)
    private String scope;

    @Column(nullable = false)
    private Instant updatedAt;
}
