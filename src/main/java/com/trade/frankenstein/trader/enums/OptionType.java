package com.trade.frankenstein.trader.enums;

public enum OptionType {

    CALL("CE", "Call European", true),
    PUT("PE", "Put European", false);

    private final String nseCode;
    private final String description;
    private final boolean isCall;

    OptionType(String nseCode, String description, boolean isCall) {
        this.nseCode = nseCode;
        this.description = description;
        this.isCall = isCall;
    }

    public String getNseCode() {
        return nseCode;
    }

    public String getDescription() {
        return description;
    }

    public boolean isCall() {
        return isCall;
    }

    public boolean isPut() {
        return !isCall;
    }

    /**
     * Parse option type from symbol suffix
     * Example: NIFTY2410024300CE -> CALL, BANKNIFTY2410050000PE -> PUT
     */
    public static OptionType fromSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("Symbol cannot be null");
        }

        String upperSymbol = symbol.toUpperCase();
        if (upperSymbol.endsWith("CE")) {
            return CALL;
        } else if (upperSymbol.endsWith("PE")) {
            return PUT;
        } else {
            throw new IllegalArgumentException("Cannot determine option type from symbol: " + symbol);
        }
    }

    /**
     * Parse option type from string representation
     */
    public static OptionType fromString(String typeStr) {
        if (typeStr == null) {
            throw new IllegalArgumentException("Type string cannot be null");
        }

        String upperType = typeStr.toUpperCase();
        switch (upperType) {
            case "CALL":
            case "CE":
            case "C":
                return CALL;
            case "PUT":
            case "PE":
            case "P":
                return PUT;
            default:
                throw new IllegalArgumentException("Unknown option type: " + typeStr);
        }
    }
}
