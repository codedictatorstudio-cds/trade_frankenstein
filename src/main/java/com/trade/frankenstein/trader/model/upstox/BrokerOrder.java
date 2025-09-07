package com.trade.frankenstein.trader.model.upstox;

import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderStatus;
import com.trade.frankenstein.trader.enums.OrderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Builder
@Data
@AllArgsConstructor
public class BrokerOrder {

    public final String orderId;
    public final String instrumentToken;
    public final String tradingSymbol;
    public final String exchange;
    public final int quantity;
    public final int filledQuantity;
    public final int pendingQuantity;
    public final OrderSide side;
    public final OrderType orderType;
    public final String product;
    public final String validity;
    public final BigDecimal price;
    public final BigDecimal triggerPrice;
    public final BigDecimal averagePrice;
    public final boolean isAmo;
    public final String tag;
    public final OrderStatus status;
    public final String orderTimestamp;
    public final String exchangeTimestamp;

    public static BrokerOrder from(UpstoxOrder o) {
        return BrokerOrder.builder()
                .orderId(o.orderId)
                .instrumentToken(o.instrumentToken)
                .tradingSymbol(o.tradingSymbol)
                .exchange(o.exchange)
                .quantity(o.quantity)
                .filledQuantity(UpstoxMapper.nz(o.filledQuantity))
                .pendingQuantity(UpstoxMapper.nz(o.pendingQuantity))
                .side(UpstoxMapper.parseSide(o.transactionType))
                .orderType(UpstoxMapper.parseOrderType(o.orderType))
                .product(o.product)
                .validity(o.validity)
                .price(o.price)
                .triggerPrice(o.triggerPrice)
                .averagePrice(o.averagePrice)
                .isAmo(Boolean.TRUE.equals(o.isAmo))
                .tag(o.tag)
                .status(UpstoxMapper.parseStatus(o.status))
                .orderTimestamp(o.orderTimestamp)
                .exchangeTimestamp(o.exchangeTimestamp)
                .build();
    }
}
