package com.trade.frankenstein.trader.model.entity;

import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "trades",
        indexes = {
                @Index(name = "ux_trades_public_id", columnList = "publicId", unique = true),
                @Index(name = "ux_trades_broker_trade_id", columnList = "brokerTradeId", unique = true),
                @Index(name = "ix_trades_filled_at", columnList = "filledAt"),
                @Index(name = "ix_trades_status", columnList = "status")
        })
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public, API-facing id (for card links) */
    @Column(nullable = false, length = 64, unique = true)
    private String publicId;

    /** Broker trade id if any */
    @Column(length = 64, unique = true)
    private String brokerTradeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private OrderEntity order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id")
    private InstrumentEntity instrument;

    @Column(nullable = false, length = 64)
    private String instrumentSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OrderSide side;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal entryPrice;

    /** Optional if still open; UI RecentTrades shows “Current” separately via LTP */
    @Column(precision = 19, scale = 4)
    private BigDecimal exitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TradeStatus status;

    /** When first fill happened */
    @Column
    private Instant filledAt;

    /** When position closed (if applicable) */
    @Column
    private Instant closedAt;

    /** Convenience snapshot fields for fast UI reads; optional */
    @Column(precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
