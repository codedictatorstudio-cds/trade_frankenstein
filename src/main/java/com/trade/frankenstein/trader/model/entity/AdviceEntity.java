package com.trade.frankenstein.trader.model.entity;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderType;
import com.trade.frankenstein.trader.enums.StrategyName;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "advices",
        indexes = {
                @Index(name = "ux_advices_public_id", columnList = "publicId", unique = true),
                @Index(name = "ix_advices_created_at", columnList = "createdAt"),
                @Index(name = "ix_advices_status", columnList = "status")
        })
public class AdviceEntity {

    /**
     * DB PK
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Public, API-facing id shown in the UI (ULID/UUID).
     */
    @Column(nullable = false, length = 64, unique = true)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id")
    private InstrumentEntity instrument;

    /**
     * Denormalized symbol for fast reads in UI tables
     */
    @Column(length = 64)
    private String instrumentSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OrderType orderType;

    /**
     * 0..100
     */
    @Column(nullable = false)
    private int confidence;

    /**
     * 0..100
     */
    @Column(nullable = false)
    private int techScore;

    /**
     * 0..100
     */
    @Column(nullable = false)
    private int newsScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdviceStatus status;

    /**
     * Human-readable reason (shown ellipsized in the card)
     */
    @Column(length = 1024)
    private String reason;

    /**
     * If promoted to an order, store broker/system order id for the UI card
     */
    @Column(length = 64)
    private String orderPublicId;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private StrategyName strategy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 64)
    private BigDecimal price;

    private Integer lots;
}
