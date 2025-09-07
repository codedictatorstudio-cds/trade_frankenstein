package com.trade.frankenstein.trader.model.entity.market;

import com.trade.frankenstein.trader.enums.OptionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "option_chain_snapshots",
        indexes = {
                @Index(name = "ix_chain_asof_underlying", columnList = "asOf DESC, underlyingSymbol"),
                @Index(name = "ix_chain_expiry", columnList = "expiry"),
                @Index(name = "ix_chain_contract", columnList = "contractSymbol")
        })
public class OptionChainSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Snapshot instant (UTC)
     */
    @Column(nullable = false)
    private Instant asOf;

    /**
     * "NIFTY"
     */
    @Column(nullable = false, length = 32)
    private String underlyingSymbol;

    /**
     * Contract symbol e.g., "NIFTY24SEP24950CE"
     */
    @Column(nullable = false, length = 64)
    private String contractSymbol;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal strike;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private OptionType optionType;

    @Column(precision = 19, scale = 4)
    private BigDecimal ltp;

    @Column
    private Long volume;

    @Column
    private Long openInterest;
}
