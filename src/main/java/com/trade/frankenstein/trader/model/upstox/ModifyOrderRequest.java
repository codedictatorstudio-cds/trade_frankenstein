package com.trade.frankenstein.trader.model.upstox;

import com.trade.frankenstein.trader.enums.OrderType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModifyOrderRequest {

    public Integer quantity;

    @NotNull
    public String validity;

    @NotNull
    public BigDecimal price;

    @NotNull
    public String order_id;

    @NotNull
    private OrderType order_type;

    public int disclosed_quantity;

    @NotNull
    public int trigger_price;
}
