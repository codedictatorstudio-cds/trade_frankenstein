package com.trade.frankenstein.trader.model.entity;

import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderStatus;
import com.trade.frankenstein.trader.enums.OrderType;
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
@Table(name = "orders",
        indexes = {
                @Index(name = "ux_orders_public_id", columnList = "publicId", unique = true),
                @Index(name = "ux_orders_broker_order_id", columnList = "brokerOrderId", unique = true),
                @Index(name = "ix_orders_status", columnList = "status"),
                @Index(name = "ix_orders_created_at", columnList = "createdAt")
        })
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Public, API-facing id
     */
    @Column(nullable = false, length = 64, unique = true)
    private String publicId;

    /**
     * Upstox (or mock) broker-provided order id if available
     */
    @Column(length = 64, unique = true)
    private String brokerOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id")
    private InstrumentEntity instrument;

    @Column(nullable = false, length = 64)
    private String instrumentSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advice_id")
    private AdviceEntity advice;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
