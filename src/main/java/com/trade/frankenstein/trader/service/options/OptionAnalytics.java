package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.dto.OptionsChainAnalysisResult;

public interface OptionAnalytics {

    OptionsChainAnalysisResult analyze(Object chainData);

    String getName();

    double getVersion();
}
