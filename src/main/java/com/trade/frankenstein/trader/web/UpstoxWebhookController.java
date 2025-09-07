package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.service.upstox.UpstoxSessionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/broker/upstox")
public class UpstoxWebhookController {

    private final UpstoxSessionService session;

    public UpstoxWebhookController(UpstoxSessionService session) {
        this.session = session;
    }

    @PostMapping("/token")
    public Result<Void> updateToken(@RequestBody TokenDto dto) {
        if (dto == null || dto.accessToken == null || dto.accessToken.isBlank()) {
            return Result.fail("BAD_REQUEST", "accessToken is required");
        }
        session.updateAccessToken(dto.accessToken);
        return Result.ok();
    }

    public static class TokenDto {
        public String accessToken;
    }
}
