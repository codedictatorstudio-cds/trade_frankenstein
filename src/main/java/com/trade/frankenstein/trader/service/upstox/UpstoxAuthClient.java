package com.trade.frankenstein.trader.service.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal client for Upstox login endpoints used to INITIATE token refresh.
 * This does not parse the token itself (tokens come via your notifier webhook).
 */
@Component
public class UpstoxAuthClient {

    private static final String DEFAULT_BASE = "https://api.upstox.com";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ObjectMapper mapper;


    private final String baseUrl = DEFAULT_BASE;
    private final Duration timeout = DEFAULT_TIMEOUT;


    /**
     * POST /v3/login/auth/token/request/{appId}
     * Body: { "client_secret": "<secret>" }
     * <p>
     * If 2xx, the request is accepted; user must approve and Upstox will POST the new token
     * to your configured notifier webhook. This method returns OK once the request is queued.
     */
    public Result<Void> requestAccessToken(String appId, String clientSecret) {
        try {
            if (appId == null || appId.isBlank()) return Result.fail("BAD_REQUEST", "appId required");
            if (clientSecret == null || clientSecret.isBlank())
                return Result.fail("BAD_REQUEST", "clientSecret required");

            String url = baseUrl + "/v3/login/auth/token/request/" + appId;
            String body = mapper.writeValueAsString(Map.of("client_secret", clientSecret));

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return Result.fail("HTTP_" + resp.statusCode(), resp.body());
            }
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e);
        }
    }
}
