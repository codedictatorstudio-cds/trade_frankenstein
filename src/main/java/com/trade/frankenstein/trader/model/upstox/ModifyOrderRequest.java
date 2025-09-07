package com.trade.frankenstein.trader.model.upstox;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public final class ModifyOrderRequest {

    public final String orderId;          // required
    public final Integer quantity;        // optional
    public final BigDecimal price;        // optional
    public final BigDecimal triggerPrice; // optional
    public final String validity;         // optional
    public final String tag;              // optional

    public Map<String, Object> toUpstoxPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", orderId);
        if (quantity != null) m.put("quantity", quantity);
        if (price != null) m.put("price", price);
        if (triggerPrice != null) m.put("trigger_price", triggerPrice);
        if (validity != null) m.put("validity", validity);
        if (tag != null) m.put("tag", tag);
        return m;
    }


}
