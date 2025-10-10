package com.trade.frankenstein.trader.test.service;

import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.service.StrategyService;
import com.trade.frankenstein.trader.service.StrategyService.Bias;
import com.trade.frankenstein.trader.service.StrategyService.LegSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyChooseLegsTest {

    @Test
    void quietMarketPicksAtmStraddle_whenBiasIsBoth() {
        StrategyService svc = new StrategyService();
        LocalDate expiry = LocalDate.now().plusDays(3);
        BigDecimal atm = BigDecimal.valueOf(20000);
        BigDecimal atrPctQuiet = new BigDecimal("0.50"); // quiet regime

        // For an ATM straddle, request step 0
        List<LegSpec> legs = svc.chooseLegs(Bias.BOTH, expiry, atm, 0, atrPctQuiet);

        assertThat(legs).isNotEmpty();
        assertThat(legs.stream().anyMatch(l -> l.getType() == OptionType.CALL)).isTrue();
        assertThat(legs.stream().anyMatch(l -> l.getType() == OptionType.PUT)).isTrue();

        // All legs should share the same strike (ATM straddle)
        Set<BigDecimal> strikes = legs.stream().map(LegSpec::getStrike).collect(Collectors.toSet());
        assertThat(strikes).hasSize(1); // same strike for both legs
    }

    @Test
    void volatileMarketPicksPlusMinusOneStrangle_whenBiasIsBoth() {
        StrategyService svc = new StrategyService();
        LocalDate expiry = LocalDate.now().plusDays(3);
        BigDecimal atm = BigDecimal.valueOf(20000);
        BigDecimal atrPctVol = new BigDecimal("2.50"); // volatile regime

        // For a ±1 strangle, request step 1
        List<LegSpec> legs = svc.chooseLegs(Bias.BOTH, expiry, atm, 1, atrPctVol);

        assertThat(legs).isNotEmpty();
        assertThat(legs.stream().anyMatch(l -> l.getType() == OptionType.CALL)).isTrue();
        assertThat(legs.stream().anyMatch(l -> l.getType() == OptionType.PUT)).isTrue();

        // Strangle → at least two distinct strikes, and at least one off the ATM level
        Set<BigDecimal> strikes = legs.stream().map(LegSpec::getStrike).collect(Collectors.toSet());
        assertThat(strikes.size()).isGreaterThanOrEqualTo(2);
        assertThat(legs.stream().anyMatch(l -> l.getStrike().compareTo(atm) != 0)).isTrue();
    }
}
