// src/main/java/com/trade/frankenstein/trader/domain/instrument/OptionContractEntity.java
package com.trade.frankenstein.trader.model.entity.market;

import com.trade.frankenstein.trader.enums.OptionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "option_contracts",
        uniqueConstraints = @UniqueConstraint(name = "ux_option_contract",
                columnNames = {"symbol"}),
        indexes = {
                @Index(name = "ix_opt_underlying_expiry", columnList = "underlyingSymbol, expiry"),
                @Index(name = "ix_opt_expiry_strike_type", columnList = "expiry, strike, optionType")
        })
public class OptionContractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Broker/display symbol: e.g., "NIFTY24SEP24950CE"
     */
    @Column(nullable = false, length = 64, unique = true)
    private String symbol;

    /**
     * Underlying index symbol; for us typically "NIFTY"
     */
    @Column(nullable = false, length = 32)
    private String underlyingSymbol;

    /**
     * Contract expiry (IST calendar date)
     */
    @Column(nullable = false)
    private LocalDate expiry;

    /**
     * Strike price
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal strike;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OptionType optionType;

    /**
     * Lot size at time of listing (useful if it changes)
     */
    @Column(nullable = false)
    private Integer lotSize;
}
