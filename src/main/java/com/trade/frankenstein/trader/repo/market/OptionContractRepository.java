package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.model.entity.market.OptionContractEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionContractRepository extends JpaRepository<OptionContractEntity, Long> {

    Optional<OptionContractEntity> findBySymbol(String symbol);

    List<OptionContractEntity> findByUnderlyingSymbolAndExpiryOrderByStrikeAsc(String underlying, LocalDate expiry);

    Optional<OptionContractEntity> findByExpiryAndStrikeAndOptionType(LocalDate expiry, BigDecimal strike, OptionType type);

    @Query("select distinct c.expiry from OptionContractEntity c where c.underlyingSymbol=:u order by c.expiry asc")
    List<LocalDate> listDistinctExpiries(String u);
}
