package com.trade.frankenstein.trader.dto;

import com.trade.frankenstein.trader.model.documents.VolatilitySurface;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Enhanced OptionChainResponse with validation and surface information
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OptionChainResponse {
    private String underlying;
    private LocalDateTime timestamp;
    private List<OptionInstrument> options;
    private int totalOptions;

    // Enhanced fields
    private VolatilitySurface volatilitySurface;
    private String validationStatus; // "VALIDATED", "PARTIAL", "FAILED"
    private int validatedOptions;
    private int rejectedOptions;
    private List<String> validationSummary;

    // Performance metrics
    private long calculationTimeMs;
    private String dataQuality; // "HIGH", "MEDIUM", "LOW"
    private double averageSpread;
    private double surfaceReliability; // 0-1 score

    /**
     * Get call options only - CORRECTED VERSION
     * Uses instrument_type field to identify CE (Call European) options
     */
    public List<OptionInstrument> getCalls() {
        if (options == null) {
            return List.of();
        }

        return options.stream()
                .filter(this::isCallOption)
                .toList();
    }

    /**
     * Get put options only - CORRECTED VERSION
     * Uses instrument_type field to identify PE (Put European) options
     */
    public List<OptionInstrument> getPuts() {
        if (options == null) {
            return List.of();
        }

        return options.stream()
                .filter(this::isPutOption)
                .toList();
    }

    /**
     * Check if the response contains high quality data
     */
    public boolean isHighQuality() {
        return "HIGH".equals(dataQuality) &&
                validatedOptions > totalOptions * 0.8 &&
                (volatilitySurface != null && volatilitySurface.isReliable());
    }

    /**
     * Get options by strike price range
     */
    public List<OptionInstrument> getOptionsByStrikeRange(double minStrike, double maxStrike) {
        if (options == null) {
            return List.of();
        }

        return options.stream()
                .filter(opt -> opt.getStrikePrice() != null)
                .filter(opt -> {
                    // Handle both BigDecimal and Double strike prices
                    double strike = getStrikePriceAsDouble(opt);
                    return strike >= minStrike && strike <= maxStrike;
                })
                .toList();
    }

    /**
     * Get ATM (At The Money) options - strikes closest to spot price
     */
    public List<OptionInstrument> getATMOptions() {
        if (options == null || options.isEmpty()) {
            return List.of();
        }

        // Find spot price from underlying or any option's ltp
        double spotPrice = getSpotPrice();

        // Find the closest strike to spot
        double closestStrike = options.stream()
                .filter(opt -> opt.getStrikePrice() != null)
                .mapToDouble(this::getStrikePriceAsDouble)
                .min()
                .orElse(spotPrice);

        double minDiff = Double.MAX_VALUE;
        for (OptionInstrument option : options) {
            if (option.getStrikePrice() != null) {
                double diff = Math.abs(getStrikePriceAsDouble(option) - spotPrice);
                if (diff < minDiff) {
                    minDiff = diff;
                    closestStrike = getStrikePriceAsDouble(option);
                }
            }
        }

        final double atmStrike = closestStrike;
        return options.stream()
                .filter(opt -> opt.getStrikePrice() != null)
                .filter(opt -> Math.abs(getStrikePriceAsDouble(opt) - atmStrike) < 0.01)
                .toList();
    }

    /**
     * Get ITM (In The Money) call options
     */
    public List<OptionInstrument> getITMCalls() {
        double spotPrice = getSpotPrice();
        return getCalls().stream()
                .filter(opt -> getStrikePriceAsDouble(opt) < spotPrice)
                .toList();
    }

    /**
     * Get OTM (Out of The Money) call options
     */
    public List<OptionInstrument> getOTMCalls() {
        double spotPrice = getSpotPrice();
        return getCalls().stream()
                .filter(opt -> getStrikePriceAsDouble(opt) > spotPrice)
                .toList();
    }

    /**
     * Get ITM (In The Money) put options
     */
    public List<OptionInstrument> getITMPuts() {
        double spotPrice = getSpotPrice();
        return getPuts().stream()
                .filter(opt -> getStrikePriceAsDouble(opt) > spotPrice)
                .toList();
    }

    /**
     * Get OTM (Out of The Money) put options
     */
    public List<OptionInstrument> getOTMPuts() {
        double spotPrice = getSpotPrice();
        return getPuts().stream()
                .filter(opt -> getStrikePriceAsDouble(opt) < spotPrice)
                .toList();
    }

    /**
     * Helper method to identify call options using Indian market standards
     * Checks instrument_type field for "CE" (Call European) or trading_symbol suffix
     */
    private boolean isCallOption(OptionInstrument option) {
        // Method 1: Check instrument_type field (Upstox standard)
        try {
            String instrumentType = getInstrumentType(option);
            if (instrumentType != null) {
                return "CE".equalsIgnoreCase(instrumentType.trim());
            }
        } catch (Exception e) {
            // Fall through to other methods
        }

        // Method 2: Check trading_symbol suffix (NSE standard)
        try {
            String tradingSymbol = getTradingSymbol(option);
            if (tradingSymbol != null) {
                return tradingSymbol.toUpperCase().endsWith("CE");
            }
        } catch (Exception e) {
            // Fall through to other methods
        }

        // Method 3: Check symbol field as fallback
        try {
            String symbol = getSymbol(option);
            if (symbol != null) {
                return symbol.toUpperCase().endsWith("CE");
            }
        } catch (Exception e) {
            // Ignore
        }

        return false; // Default to false if cannot determine
    }

    /**
     * Helper method to identify put options using Indian market standards
     */
    private boolean isPutOption(OptionInstrument option) {
        // Method 1: Check instrument_type field (Upstox standard)
        try {
            String instrumentType = getInstrumentType(option);
            if (instrumentType != null) {
                return "PE".equalsIgnoreCase(instrumentType.trim());
            }
        } catch (Exception e) {
            // Fall through to other methods
        }

        // Method 2: Check trading_symbol suffix (NSE standard)
        try {
            String tradingSymbol = getTradingSymbol(option);
            if (tradingSymbol != null) {
                return tradingSymbol.toUpperCase().endsWith("PE");
            }
        } catch (Exception e) {
            // Fall through to other methods
        }

        // Method 3: Check symbol field as fallback
        try {
            String symbol = getSymbol(option);
            if (symbol != null) {
                return symbol.toUpperCase().endsWith("PE");
            }
        } catch (Exception e) {
            // Ignore
        }

        return false; // Default to false if cannot determine
    }

    /**
     * Helper method to get strike price as double, handling different data types
     */
    private double getStrikePriceAsDouble(OptionInstrument option) {
        try {
            Object strikePrice = getStrikePrice(option);
            if (strikePrice instanceof BigDecimal) {
                return ((BigDecimal) strikePrice).doubleValue();
            } else if (strikePrice instanceof Double) {
                return (Double) strikePrice;
            } else if (strikePrice instanceof Float) {
                return ((Float) strikePrice).doubleValue();
            } else if (strikePrice instanceof Number) {
                return ((Number) strikePrice).doubleValue();
            } else if (strikePrice instanceof String) {
                return Double.parseDouble((String) strikePrice);
            }
        } catch (Exception e) {
            // Ignore and return 0
        }
        return 0.0;
    }

    /**
     * Helper method to get spot price from options or underlying
     */
    private double getSpotPrice() {
        if (options == null || options.isEmpty()) {
            return 0.0;
        }

        // Try to get from any option's underlying_price or spot_price field
        for (OptionInstrument option : options) {
            try {
                Object spotPrice = getUnderlyingPrice(option);
                if (spotPrice != null) {
                    if (spotPrice instanceof BigDecimal) {
                        return ((BigDecimal) spotPrice).doubleValue();
                    } else if (spotPrice instanceof Number) {
                        return ((Number) spotPrice).doubleValue();
                    }
                }
            } catch (Exception e) {
                // Continue to next option
            }
        }

        // Fallback: estimate from ATM strike prices
        return options.stream()
                .mapToDouble(this::getStrikePriceAsDouble)
                .average()
                .orElse(0.0);
    }

    // Reflection helper methods to handle different possible field names

    private String getInstrumentType(OptionInstrument option) {
        return getStringField(option, "instrumentType", "instrument_type", "type", "optionType");
    }

    private String getTradingSymbol(OptionInstrument option) {
        return getStringField(option, "tradingSymbol", "trading_symbol", "symbol");
    }

    private String getSymbol(OptionInstrument option) {
        return getStringField(option, "symbol", "trading_symbol", "name");
    }

    private Object getStrikePrice(OptionInstrument option) {
        return getObjectField(option, "strikePrice", "strike_price", "strike");
    }

    private Object getUnderlyingPrice(OptionInstrument option) {
        return getObjectField(option, "underlyingPrice", "underlying_price", "spotPrice", "spot_price", "ltp");
    }

    /**
     * Generic helper to get string field using reflection with multiple possible field names
     */
    private String getStringField(OptionInstrument option, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                // Try getter method first
                String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                Object result = option.getClass().getMethod(methodName).invoke(option);
                if (result != null) {
                    return result.toString();
                }
            } catch (Exception e) {
                try {
                    // Try direct field access
                    java.lang.reflect.Field field = option.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object result = field.get(option);
                    if (result != null) {
                        return result.toString();
                    }
                } catch (Exception e2) {
                    // Continue to next field name
                }
            }
        }
        return null;
    }

    /**
     * Generic helper to get object field using reflection with multiple possible field names
     */
    private Object getObjectField(OptionInstrument option, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                // Try getter method first
                String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                Object result = option.getClass().getMethod(methodName).invoke(option);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                try {
                    // Try direct field access
                    java.lang.reflect.Field field = option.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object result = field.get(option);
                    if (result != null) {
                        return result;
                    }
                } catch (Exception e2) {
                    // Continue to next field name
                }
            }
        }
        return null;
    }

    /**
     * Get summary statistics
     */
    public OptionChainSummary getSummary() {
        if (options == null || options.isEmpty()) {
            return OptionChainSummary.builder().build();
        }

        List<OptionInstrument> calls = getCalls();
        List<OptionInstrument> puts = getPuts();

        double totalCallVolume = calls.stream()
                .mapToDouble(this::getVolumeAsDouble)
                .sum();

        double totalPutVolume = puts.stream()
                .mapToDouble(this::getVolumeAsDouble)
                .sum();

        double totalCallOI = calls.stream()
                .mapToDouble(this::getOpenInterestAsDouble)
                .sum();

        double totalPutOI = puts.stream()
                .mapToDouble(this::getOpenInterestAsDouble)
                .sum();

        return OptionChainSummary.builder()
                .totalOptions(totalOptions)
                .totalCalls(calls.size())
                .totalPuts(puts.size())
                .totalCallVolume(totalCallVolume)
                .totalPutVolume(totalPutVolume)
                .totalCallOI(totalCallOI)
                .totalPutOI(totalPutOI)
                .putCallVolumeRatio(totalCallVolume > 0 ? totalPutVolume / totalCallVolume : 0.0)
                .putCallOIRatio(totalCallOI > 0 ? totalPutOI / totalCallOI : 0.0)
                .dataQuality(dataQuality)
                .spotPrice(getSpotPrice())
                .build();
    }

    /**
     * Helper to get volume as double
     */
    private double getVolumeAsDouble(OptionInstrument option) {
        Object volume = getObjectField(option, "volume", "totalTradedVolume", "vol");
        if (volume instanceof Number) {
            return ((Number) volume).doubleValue();
        }
        return 0.0;
    }

    /**
     * Helper to get open interest as double
     */
    private double getOpenInterestAsDouble(OptionInstrument option) {
        Object oi = getObjectField(option, "openInterest", "oi", "open_interest");
        if (oi instanceof Number) {
            return ((Number) oi).doubleValue();
        }
        return 0.0;
    }

    /**
     * Inner class for option chain summary statistics
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionChainSummary {
        private int totalOptions;
        private int totalCalls;
        private int totalPuts;
        private double totalCallVolume;
        private double totalPutVolume;
        private double totalCallOI;
        private double totalPutOI;
        private double putCallVolumeRatio;
        private double putCallOIRatio;
        private String dataQuality;
        private double spotPrice;
    }

    /**
     * Placeholder OptionInstrument class for reference
     * This should match your actual OptionInstrument model
     */
    public static class OptionInstrument {
        // Common field names based on Upstox API and NSE standards:
        // - instrument_key, trading_symbol, strike_price, instrument_type
        // - last_price (ltp), bid_price, ask_price, volume, oi
        // - underlying_symbol, underlying_key, expiry, lot_size
        // - name, exchange, segment, tick_size, freeze_quantity

        public Object getStrikePrice() {
            return null;
        }

        public String getInstrumentType() {
            return null;
        }

        public String getTradingSymbol() {
            return null;
        }

        public String getSymbol() {
            return null;
        }

        public Object getVolume() {
            return null;
        }

        public Object getOpenInterest() {
            return null;
        }

        public Object getUnderlyingPrice() {
            return null;
        }
        // Add other getters as needed based on your actual model
    }
}