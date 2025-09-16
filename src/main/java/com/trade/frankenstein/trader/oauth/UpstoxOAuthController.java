package com.trade.frankenstein.trader.oauth;

import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.constants.UpstoxConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@Slf4j
@RestController
public class UpstoxOAuthController {

    @GetMapping("/oauth/upstox/login")
    public RedirectView login() {
        String url = UpstoxConstants.AUTHORIZATION_URL +
                "?response_type=code" +
                "&client_id=" + UpstoxConstants.CLIENT_ID +
                "&redirect_uri=" + UpstoxConstants.REDIRECT_URL;

        return new RedirectView(url);
    }

    @GetMapping("/oauth/upstox/callback")
    public RedirectView callback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String error,
                                 HttpServletRequest request) {
        if (error != null || code == null) {
            return new RedirectView("/?error=1");
        }
        log.info("Received OAuth callback with code: {}", code);
        if(!code.isEmpty() && !code.isBlank()) {
            AuthCodeHolder holder = AuthCodeHolder.getInstance();
            holder.set(code);
        }
        return new RedirectView("/?code=" + code);
    }
}
