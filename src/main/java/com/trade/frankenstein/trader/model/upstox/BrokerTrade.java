package com.trade.frankenstein.trader.model.upstox;


import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Builder
@Data
@AllArgsConstructor
public final class BrokerTrade {

    public final String tradeId;
    public final String orderId;
    public final String instrumentToken;
    public final String tradingSymbol;
    public final String exchange;
    public final OrderSide side;
    public final OrderType orderType;
    public final int quantity;
    public final BigDecimal averagePrice;
    public final String exchangeTimestamp;
    public final String orderTimestamp;

    public static BrokerTrade from(UpstoxTrade t) {
        return BrokerTrade.builder()
                .tradeId(t.tradeId)
                .orderId(t.orderId)
                .instrumentToken(t.instrumentToken)
                .tradingSymbol(t.tradingSymbol)
                .exchange(t.exchange)
                .side(UpstoxMapper.parseSide(t.transactionType))
                .orderType(UpstoxMapper.parseOrderType(t.orderType))
                .quantity(t.quantity)
                .averagePrice(t.averagePrice)
                .exchangeTimestamp(t.exchangeTimestamp)
                .orderTimestamp(t.orderTimestamp)
                .build();
    }

}
