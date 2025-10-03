package com.trade.frankenstein.trader.enums;

/**
 * Indicates the net portfolio directional bias.
 */
public enum PortfolioBias {
    BULL,    // Net long exposure
    BEAR,    // Net short exposure
    NEUTRAL; // Balanced or no directional exposure

    /**
     * Derives bias from a net delta value.
     *
     * @param netDelta positive for net long, negative for net short
     * @return corresponding PortfolioBias
     */
    public static PortfolioBias fromNetDelta(double netDelta) {
        if (netDelta > 0) {
            return BULL;
        } else if (netDelta < 0) {
            return BEAR;
        } else {
            return NEUTRAL;
        }
    }
}
