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
@Table(name = "exchange_holidays",
        uniqueConstraints = @UniqueConstraint(name = "ux_holiday_date", columnNames = {"holidayDate"}),
        indexes = {@Index(name = "ix_holiday_date", columnList = "holidayDate")})
public class ExchangeHolidayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Holiday/halt date (IST calendar)
     */
    @Column(nullable = false)
    private LocalDate holidayDate;

    @Column(length = 128)
    private String reason;
}
