package com.trade.frankenstein.trader.test.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.service.FlagsService;
import com.trade.frankenstein.trader.service.RiskService;
import com.trade.frankenstein.trader.service.StreamGateway;
import com.trade.frankenstein.trader.service.UpstoxService;
import com.upstox.api.PlaceOrderRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskServiceThrottleTest {

    @Test
    void checkOrderFailsWhenThrottleAtOrAbove100pct() {
        UpstoxService upstox = mock(UpstoxService.class);
        StreamGateway stream = mock(StreamGateway.class);
        FastStateStore fast = mock(FastStateStore.class);
        FlagsService flags = mock(FlagsService.class);

        // Simulate Redis counter already >= cap (by returning a big number string)
        when(fast.get("orders_per_min")).thenReturn(Optional.of("9999"));

        RiskService risk = new RiskService(upstox, stream, fast, flags);
        PlaceOrderRequest draft = new PlaceOrderRequest();
        draft.setInstrumentToken("token");

        Result<Void> r = risk.checkOrder(draft);

        assertThat(r.isOk()).isFalse();
        assertThat(r.getErrorCode()).isEqualTo("THROTTLED");
    }
}
