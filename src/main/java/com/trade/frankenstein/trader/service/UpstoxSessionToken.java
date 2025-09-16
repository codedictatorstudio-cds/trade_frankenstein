package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.constants.UpstoxConstants;
import com.trade.frankenstein.trader.model.upstox.AuthenticationResponse;
import com.trade.frankenstein.trader.model.upstox.UpstoxAuthResponse;
import com.trade.frankenstein.trader.repo.upstox.AuthenticationResponseRepo;
import com.trade.frankenstein.trader.repo.upstox.UpstoxAuthResponseRepo;
import com.upstox.api.TokenResponse;
import io.swagger.client.api.LoginApi;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Component
@Slf4j
public class UpstoxSessionToken {

    @Autowired
    private AuthenticationResponseRepo authenticationResponseRepo; // Repository for authentication responses

    @Autowired
    private UpstoxAuthResponseRepo upstoxAuthResponseRepo;

    @Autowired
    private ObjectMapper mapper; // For JSON mapping

    @Autowired
    private RestTemplate restTemplate; // For HTTP requests

    // Generates and saves a new access token
    @SneakyThrows
    public void generateAccessToken() {

        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in. Cannot generate access token.");
            return;
        }

        LoginApi apiInstance = new LoginApi();
        String apiVersion = "2.0";
        String code = AuthCodeHolder.getInstance().peek();
        String clientId = UpstoxConstants.CLIENT_ID;
        String clientSecret = UpstoxConstants.SECRET_KEY;
        String redirectUri = UpstoxConstants.REDIRECT_URL;
        String grantType = "authorization_code";

        TokenResponse result = apiInstance.token(apiVersion, code, clientId, clientSecret, redirectUri, grantType);
        System.out.println(" Token Result " + result);
        log.info("Received Upstox token response");

        UpstoxAuthResponse accessToken = mapper.convertValue(result, UpstoxAuthResponse.class);
        log.info("Mapped access token");

        upstoxAuthResponseRepo.deleteAll();
        accessToken = upstoxAuthResponseRepo.save(accessToken);
        log.info("Access Token saved to DB");

        AuthenticationResponse authRecord = AuthenticationResponse.builder()
                .response(accessToken)
                .createdDate(Instant.now())
                .build();

        authenticationResponseRepo.deleteAll();
        authenticationResponseRepo.save(authRecord);
        log.info("Access Token saved");

    }


}
