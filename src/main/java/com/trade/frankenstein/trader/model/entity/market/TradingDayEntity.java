package com.trade.frankenstein.trader.model.entity.market;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "trading_days",
        uniqueConstraints = @UniqueConstraint(name = "ux_trading_day", columnNames = {"tradeDate"}),
        indexes = {@Index(name = "ix_trading_day_date", columnList = "tradeDate")})
public class TradingDayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Exchange trading date (IST calendar)
     */
    @Column(nullable = false)
    private LocalDate tradeDate;

    /**
     * True if regular session open (some days are partial or closed)
     */
    @Column(nullable = false)
    private boolean open;

    /**
     * Optional note (e.g., "Special Muhurat Trading")
     */
    @Column(length = 128)
    private String note;
}
