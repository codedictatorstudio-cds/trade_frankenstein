package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.documents.MarketMicrostructure;
import com.trade.frankenstein.trader.model.documents.OrderBookDepth;
import com.trade.frankenstein.trader.model.documents.PriceLevel;
import com.trade.frankenstein.trader.service.OptionChainService;
import com.trade.frankenstein.trader.service.OrdersService;
import com.trade.frankenstein.trader.service.UpstoxService;
import com.upstox.api.GetMarketQuoteLastTradedPriceResponseV3;
import com.upstox.api.InstrumentData;
import com.upstox.api.MarketQuoteOptionGreekV3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@Slf4j
public class MarketMicrostructureService {

    @Autowired
    private UpstoxService upstoxService;

    @Autowired
    private OptionChainService optionChainService;

    @Autowired
    private OrdersService ordersService;

    public MarketMicrostructure analyze(String symbol, LocalDate expiry) {
        try {
            MarketMicrostructure microStructure = new MarketMicrostructure();
            microStructure.setSymbol(symbol);
            microStructure.setTimestamp(Instant.now());

            // Get market depth data
            OrderBookDepth depth = getOrderBookDepth(symbol);
            microStructure.setDepth(depth);

            // Calculate imbalance
            BigDecimal imbalance = calculateOrderBookImbalance(depth);
            microStructure.setImbalance(imbalance);

            // Calculate liquidity score
            BigDecimal liquidityScore = calculateLiquidityScore(symbol, expiry);
            microStructure.setLiquidityScore(liquidityScore);

            // Get spread information
            BigDecimal spread = getEffectiveSpread(symbol);
            microStructure.setSpread(spread);
            microStructure.setEffectiveSpread(spread);

            // Get volume data
            Long totalVolume = getTotalVolume(symbol);
            microStructure.setTotalVolume(totalVolume);

            // Build price levels
            List<PriceLevel> levels = buildPriceLevels(depth);
            microStructure.setLevels(levels);

            return microStructure;

        } catch (Exception e) {
            log.error("Failed to analyze market microstructure for {}: {}", symbol, e.getMessage());
            return createDefaultMicrostructure(symbol);
        }
    }

    private OrderBookDepth getOrderBookDepth(String symbol) {
        try {
            OrderBookDepth depth = new OrderBookDepth();

            // Simulate order book depth - in reality, get from market data feed
            List<PriceLevel> bids = new ArrayList<>();
            List<PriceLevel> asks = new ArrayList<>();

            // Get current LTP as reference
            BigDecimal basePrice = getCurrentPrice(symbol);
            if (basePrice == null) basePrice = bd("19000"); // Fallback

            // Generate simulated bid levels
            Random random = new Random();
            BigDecimal bidVolume = BigDecimal.ZERO;
            for (int i = 1; i <= 5; i++) {
                BigDecimal price = basePrice.subtract(bd(String.valueOf(i * 0.5)));
                BigDecimal quantity = bd(String.valueOf(1000 + random.nextInt(5000)));
                int orderCount = 5 + random.nextInt(20);

                PriceLevel bid = new PriceLevel();
                bid.setPrice(price);
                bid.setQuantity(quantity);
                bid.setOrderCount(orderCount);
                bids.add(bid);
                bidVolume = bidVolume.add(quantity);
            }

            // Generate simulated ask levels
            BigDecimal askVolume = BigDecimal.ZERO;
            for (int i = 1; i <= 5; i++) {
                BigDecimal price = basePrice.add(bd(String.valueOf(i * 0.5)));
                BigDecimal quantity = bd(String.valueOf(1000 + random.nextInt(5000)));
                int orderCount = 5 + random.nextInt(20);

                PriceLevel ask = new PriceLevel();
                ask.setPrice(price);
                ask.setQuantity(quantity);
                ask.setOrderCount(orderCount);
                asks.add(ask);
                askVolume = askVolume.add(quantity);
            }

            depth.setBids(bids);
            depth.setAsks(asks);
            depth.setBidVolume(bidVolume);
            depth.setAskVolume(askVolume);

            // Calculate mid price
            if (!bids.isEmpty() && !asks.isEmpty()) {
                BigDecimal bestBid = bids.get(0).getPrice();
                BigDecimal bestAsk = asks.get(0).getPrice();
                depth.setMidPrice(bestBid.add(bestAsk).divide(bd("2"), 2, java.math.RoundingMode.HALF_UP));
            }

            return depth;

        } catch (Exception e) {
            log.error("Failed to get order book depth: {}", e.getMessage());
            return new OrderBookDepth();
        }
    }

    private BigDecimal calculateOrderBookImbalance(OrderBookDepth depth) {
        try {
            if (depth == null || depth.getBidVolume() == null || depth.getAskVolume() == null) {
                return bd("0.0");
            }

            BigDecimal totalVolume = depth.getBidVolume().add(depth.getAskVolume());
            if (totalVolume.compareTo(BigDecimal.ZERO) == 0) {
                return bd("0.0");
            }

            // Imbalance = (BidVolume - AskVolume) / TotalVolume
            // Range: -1.0 to +1.0
            BigDecimal imbalance = depth.getBidVolume().subtract(depth.getAskVolume())
                    .divide(totalVolume, 4, java.math.RoundingMode.HALF_UP);

            return imbalance;

        } catch (Exception e) {
            log.error("Failed to calculate order book imbalance: {}", e.getMessage());
            return bd("0.0");
        }
    }

    private BigDecimal calculateLiquidityScore(String symbol, LocalDate expiry) {
        try {
            // Base liquidity score on multiple factors
            double score = 0.5; // Base score

            // Factor 1: Option chain depth using available method
            BigDecimal spot = getCurrentPrice(symbol);
            if (spot != null) {
                BigDecimal atm = bd(String.valueOf(Math.round(spot.doubleValue() / 50.0) * 50)); // Round to nearest 50
                BigDecimal rangeWidth = bd("500"); // +/- 500 points from ATM

                Result<List<InstrumentData>> chainResult = optionChainService.listContractsByStrikeRange(
                        symbol, expiry, atm.subtract(rangeWidth), atm.add(rangeWidth));

                if (chainResult != null && chainResult.isOk() && chainResult.get() != null) {
                    int chainSize = chainResult.get().size();
                    if (chainSize > 50) score += 0.2;
                    else if (chainSize > 30) score += 0.1;
                    else if (chainSize < 10) score -= 0.2;
                }
            }

            // Factor 2: Average OI across strikes
            double avgOI = getAverageOpenInterest(symbol, expiry);
            if (avgOI > 50000) score += 0.2;
            else if (avgOI > 20000) score += 0.1;
            else if (avgOI < 5000) score -= 0.3;

            // Factor 3: Bid-ask spreads
            BigDecimal avgSpread = getAverageSpread(symbol, expiry);
            if (avgSpread != null) {
                if (avgSpread.compareTo(bd("0.02")) <= 0) score += 0.1; // Tight spreads
                else if (avgSpread.compareTo(bd("0.05")) > 0) score -= 0.2; // Wide spreads
            }

            // Factor 4: Time of day effect
            int hour = java.time.LocalTime.now().getHour();
            if (hour >= 9 && hour <= 15) {
                if (hour >= 9 && hour <= 11) score += 0.1; // Morning session
                if (hour >= 14 && hour <= 15) score += 0.05; // Closing session
            } else {
                score -= 0.3; // Outside market hours
            }

            // Clamp score between 0 and 1
            score = Math.max(0.0, Math.min(1.0, score));

            return bd(String.valueOf(score));

        } catch (Exception e) {
            log.error("Failed to calculate liquidity score: {}", e.getMessage());
            return bd("0.5"); // Default neutral score
        }
    }

    private BigDecimal getAverageSpread(String symbol, LocalDate expiry) {
        try {
            // Use listContractsByStrikeRange to get contracts near ATM
            BigDecimal spot = getCurrentPrice(symbol);
            if (spot != null) {
                BigDecimal atm = bd(String.valueOf(Math.round(spot.doubleValue() / 50.0) * 50));
                BigDecimal rangeWidth = bd("200"); // Smaller range for spread calculation

                Result<List<InstrumentData>> chainResult = optionChainService.listContractsByStrikeRange(
                        symbol, expiry, atm.subtract(rangeWidth), atm.add(rangeWidth));

                if (chainResult != null && chainResult.isOk() && chainResult.get() != null) {
                    List<BigDecimal> spreads = new ArrayList<>();

                    for (InstrumentData inst : chainResult.get()) {
                        Optional<BigDecimal> spreadOpt = ordersService.getSpreadPct(inst.getInstrumentKey());
                        spreadOpt.ifPresent(spreads::add);
                    }

                    if (!spreads.isEmpty()) {
                        double avgSpread = spreads.stream()
                                .mapToDouble(BigDecimal::doubleValue)
                                .average()
                                .orElse(0.03);
                        return bd(String.valueOf(avgSpread));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not calculate average spread: {}", e.getMessage());
        }

        return bd("0.03"); // Default 3%
    }


    private BigDecimal getEffectiveSpread(String symbol) {
        try {
            // For index, return a simulated spread
            Random random = new Random();
            double spread = 0.01 + random.nextDouble() * 0.03; // 1-4%
            return bd(String.valueOf(spread));
        } catch (Exception e) {
            log.error("Failed to get effective spread: {}", e.getMessage());
            return bd("0.025"); // Default 2.5%
        }
    }

    private Long getTotalVolume(String symbol) {
        try {
            // Simulate total volume
            Random random = new Random();
            return (long) (100000 + random.nextInt(900000)); // 100K to 1M
        } catch (Exception e) {
            log.error("Failed to get total volume: {}", e.getMessage());
            return 500000L; // Default volume
        }
    }

    private List<PriceLevel> buildPriceLevels(OrderBookDepth depth) {
        List<PriceLevel> levels = new ArrayList<>();

        if (depth != null) {
            if (depth.getBids() != null) {
                levels.addAll(depth.getBids());
            }
            if (depth.getAsks() != null) {
                levels.addAll(depth.getAsks());
            }
        }

        // Sort by price
        levels.sort(Comparator.comparing(PriceLevel::getPrice));

        return levels;
    }

    private BigDecimal getCurrentPrice(String symbol) {
        try {
            GetMarketQuoteLastTradedPriceResponseV3 response = upstoxService.getMarketLTPQuote(symbol);
            if (response != null && response.getData() != null && response.getData().get(symbol) != null) {
                double ltp = response.getData().get(symbol).getLastPrice();
                return bd(String.valueOf(ltp));
            }
        } catch (Exception e) {
            log.debug("Could not get current price for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    private double getAverageOpenInterest(String symbol, LocalDate expiry) {
        try {
            Result<Map<String, MarketQuoteOptionGreekV3>> greeksResult =
                    optionChainService.getGreeksForExpiry(symbol, expiry);

            if (greeksResult != null && greeksResult.isOk() && greeksResult.get() != null) {
                Map<String, MarketQuoteOptionGreekV3> greeks = greeksResult.get();

                double totalOI = greeks.values().stream()
                        .mapToDouble(greek -> greek.getOi() != null ? greek.getOi().doubleValue() : 0.0)
                        .sum();

                return greeks.isEmpty() ? 0.0 : totalOI / greeks.size();
            }
        } catch (Exception e) {
            log.debug("Could not calculate average OI: {}", e.getMessage());
        }

        return 25000.0; // Default assumption
    }


    private MarketMicrostructure createDefaultMicrostructure(String symbol) {
        MarketMicrostructure defaultStructure = new MarketMicrostructure();
        defaultStructure.setSymbol(symbol);
        defaultStructure.setTimestamp(Instant.now());
        defaultStructure.setImbalance(bd("0.0"));
        defaultStructure.setLiquidityScore(bd("0.5"));
        defaultStructure.setSpread(bd("0.03"));
        defaultStructure.setEffectiveSpread(bd("0.03"));
        defaultStructure.setTotalVolume(500000L);
        defaultStructure.setLevels(new ArrayList<>());

        return defaultStructure;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
