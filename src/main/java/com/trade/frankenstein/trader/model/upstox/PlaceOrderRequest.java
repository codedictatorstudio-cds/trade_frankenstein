package com.trade.frankenstein.trader.model.upstox;

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
public class PlaceOrderRequest {

    @NotNull
    private int quantity;

    @NotNull
    private String product;

    @NotNull
    private String validity;

    @NotNull
    private BigDecimal price;

    private String tag;

    @NotNull
    private String instrument_token;

    @NotNull
    private String order_type;

    @NotNull
    private String transaction_type;

    @NotNull
    private int disclosed_quantity;

    @NotNull
    private BigDecimal trigger_price;

    @NotNull
    private boolean is_amo;

    @NotNull
    private boolean slice;

}
