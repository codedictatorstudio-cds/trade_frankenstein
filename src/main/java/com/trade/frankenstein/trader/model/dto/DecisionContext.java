package com.trade.frankenstein.trader.model.dto;

import com.trade.frankenstein.trader.enums.PortfolioBias;
import com.trade.frankenstein.trader.enums.StrategyName;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.service.PortfolioService;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class DecisionContext {
    private PortfolioService.PortfolioSummary portfolio;
    private List<Trade> activeTrades;
    private Map<StrategyName,Integer> pendingAdviceByStrategy;
    private BigDecimal totalExposure;
    private BigDecimal netDelta;
    private PortfolioBias portfolioBias;
    private Double concentrationRisk;

    public static DecisionContext empty() {
        return DecisionContext.builder().build();
    }
}
