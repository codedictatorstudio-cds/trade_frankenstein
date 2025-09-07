package com.trade.frankenstein.trader.model.upstox;


import java.util.List;

public final class PortfolioSnapshot {

    public final List<UpstoxPosition> positions;
    public final List<UpstoxHolding> holdings;

    public PortfolioSnapshot(List<UpstoxPosition> positions, List<UpstoxHolding> holdings) {
        this.positions = positions == null ? List.of() : List.copyOf(positions);
        this.holdings = holdings == null ? List.of() : List.copyOf(holdings);
    }
}
