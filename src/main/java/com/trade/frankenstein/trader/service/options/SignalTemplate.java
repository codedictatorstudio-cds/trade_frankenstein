package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.dto.OptionChainData;
import com.trade.frankenstein.trader.dto.TradingSignal;

public interface SignalTemplate {

    boolean isTriggered(OptionChainData data);

    TradingSignal generateSignal(OptionChainData data);

    String getName();

    double getThreshold();

    void setThreshold(double threshold);
}
