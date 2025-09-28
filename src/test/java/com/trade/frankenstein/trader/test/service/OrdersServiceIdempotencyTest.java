package com.trade.frankenstein.trader.test.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.service.*;
import com.upstox.api.PlaceOrderData;
import com.upstox.api.PlaceOrderRequest;
import com.upstox.api.PlaceOrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrdersServiceIdempotencyTest {

    @Mock
    UpstoxService upstox;
    @Mock
    RiskService risk;
    @Mock
    StreamGateway stream;
    @Mock
    UpstoxTradeMode tradeMode;
    @Mock
    FastStateStore fast;

    @InjectMocks
    OrdersService orders;

    PlaceOrderRequest draft;

    @BeforeEach
    void setUp() {
        draft = new PlaceOrderRequest();
        draft.setInstrumentToken("NSE_INDEX|Nifty 50");
        draft.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.BUY);
        draft.setQuantity(50);
        draft.setPrice(0F);
        draft.setIsAmo(true); // bypass market-hours in test path
        when(tradeMode.isSandBox()).thenReturn(true);
        when(risk.checkOrder(any())).thenReturn(Result.ok(null));
    }

    @Test
    void duplicateWithinWindowIsRejected() throws Exception {
        when(fast.setIfAbsent(startsWith("order:idemp:"), eq("1"), eq(Duration.ofSeconds(120))))
                .thenReturn(true)  // first call (accept)
                .thenReturn(false); // second call (dup)

        PlaceOrderResponse placed = new PlaceOrderResponse();
        PlaceOrderData data = new PlaceOrderData();
        placed.setData(data);
        when(upstox.placeOrder(any())).thenReturn(placed);

        Result<PlaceOrderResponse> r1 = orders.placeOrder(draft);
        Result<PlaceOrderResponse> r2 = orders.placeOrder(draft);

        assertThat(r1.isOk()).isTrue();
        assertThat(r2.isOk()).isFalse();
        assertThat(r2.getErrorCode()).isEqualTo("DUPLICATE");
        verify(upstox, times(1)).placeOrder(any()); // second call never reaches broker
    }
}
