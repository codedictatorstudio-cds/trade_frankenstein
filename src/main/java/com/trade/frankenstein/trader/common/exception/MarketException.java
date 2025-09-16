package com.trade.frankenstein.trader.common.exception;

/**
 * Exception thrown when a market-related operation fails.
 * This could be due to market connectivity issues, trading hours restrictions, etc.
 */
public class MarketException extends BaseTradeException {
    private static final String DEFAULT_ERROR_CODE = "ERR-MKT-001";

    public MarketException(String message) {
        super(message);
    }

    public MarketException(String message, Throwable cause) {
        super(message, cause);
    }

    public MarketException(String errorCode, String message) {
        super(errorCode, message);
    }

    public MarketException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override
    protected String getDefaultErrorCode() {
        return DEFAULT_ERROR_CODE;
    }
}
