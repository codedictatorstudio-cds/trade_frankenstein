package com.trade.frankenstein.trader.model.entity;

import com.trade.frankenstein.trader.enums.InstrumentType;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "instruments",
        indexes = {
                @Index(name = "ux_instruments_symbol", columnList = "symbol", unique = true),
                @Index(name = "ix_instruments_type", columnList = "type")
        })
public class InstrumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * e.g., "NIFTY", "NIFTY24SEP24950CE"
     */
    @Column(nullable = false, length = 64)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InstrumentType type;

    /**
     * Optional convenience for options
     */
    @Column
    private Integer lotSize;
}
