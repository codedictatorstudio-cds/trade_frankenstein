package com.trade.frankenstein.trader.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class UpstoxOAuthController {

    @Value("${upstox.client-id}")
    private String clientId;

    @Value("${upstox.redirect-uri}")
    private String redirectUri;

    @Value("${upstox.authorize-url:https://api.upstox.com/v2/login/authorization/dialog}")
    private String oauthUrl;

    @GetMapping("/oauth/upstox/login")
    public RedirectView login() {
        String url = oauthUrl +
                "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                "&response_type=code";
        return new RedirectView(url);
    }

    @GetMapping("/oauth/upstox/callback")
    public RedirectView callback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String error,
                                 HttpServletRequest request) {
        if (error != null || code == null) {
            return new RedirectView("/?error=1");
        }
        // Here you would exchange the code for an access token and validate it.
        // If successful:
        return new RedirectView("/dashboard");
        // If failed, redirect as below:
        // return new RedirectView("/?error=1");
    }
}
