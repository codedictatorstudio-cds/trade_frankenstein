package com.trade.frankenstein.trader.model.upstox;

import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderType;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @param instrumentToken instrument_key in docs
 * @param side            BUY/SELL
 * @param orderType       MARKET/LIMIT/SL/SL_LIMIT
 * @param product         "I","D","MTF"
 * @param validity        "DAY","IOC"
 * @param price           null for MARKET/SL
 * @param triggerPrice    required for SL/SL_LIMIT
 */
@Builder
public record PlaceOrderRequest(Long instrumentToken, OrderSide side, OrderType orderType, int quantity, String product,
                                String validity, BigDecimal price, BigDecimal triggerPrice, boolean isAmo,
                                boolean slice, String tag, Integer disclosedQuantity) {

    public void validate() {
        if (instrumentToken == null)
            throw new IllegalArgumentException("instrumentToken required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (orderType == null) throw new IllegalArgumentException("orderType required");
        if (side == null) throw new IllegalArgumentException("side required");

        switch (orderType) {
            case LIMIT:
                if (price == null) throw new IllegalArgumentException("price required for LIMIT");
                break;
            case STOP_MARKET:
                if (triggerPrice == null)
                    throw new IllegalArgumentException("triggerPrice required for SL (Stop-Loss Market)");
                if (price != null) throw new IllegalArgumentException("price must be null for SL (Stop-Loss Market)");
                break;
            case STOP_LIMIT:
                if (price == null || triggerPrice == null)
                    throw new IllegalArgumentException("price and triggerPrice required for SL_LIMIT (Stop-Loss Limit)");
                break;
            case MARKET:
                if (price != null || triggerPrice != null)
                    throw new IllegalArgumentException("price/triggerPrice must be null for MARKET");
                break;
        }
    }

    public Map<String, Object> toUpstoxPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quantity", quantity);
        m.put("product", product);
        m.put("validity", validity);
        m.put("price", price == null ? 0 : price);
        if (tag != null) m.put("tag", tag);
        m.put("instrument_token", instrumentToken);
        m.put("order_type", UpstoxMapper.mapOrderType(orderType));
        m.put("transaction_type", UpstoxMapper.mapSide(side));
        m.put("disclosed_quantity", disclosedQuantity == null ? 0 : disclosedQuantity);
        m.put("trigger_price", triggerPrice == null ? 0 : triggerPrice);
        m.put("is_amo", isAmo);
        m.put("slice", slice);
        return m;
    }

}
