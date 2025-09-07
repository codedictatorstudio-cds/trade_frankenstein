package com.trade.frankenstein.trader.service.upstox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.upstox.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class UpstoxClient {

    private static final String DEFAULT_BASE = "https://api.upstox.com";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    // V3 Orders
    private static final String EP_PLACE_ORDER_V3 = "/v3/order/place";
    private static final String EP_MODIFY_ORDER_V3 = "/v3/order/modify";
    private static final String EP_CANCEL_ORDER_V3 = "/v3/order/cancel";

    // V2 Orders/Trades/Portfolio
    private static final String EP_GET_ORDER_DETAILS = "/v2/order/details";
    private static final String EP_GET_ORDER_BOOK = "/v2/order/retrieve-all";
    private static final String EP_GET_TRADES_TODAY = "/v2/order/trades/get-trades-for-day";
    private static final String EP_POSITIONS = "/v2/portfolio/short-term-positions";
    private static final String EP_HOLDINGS = "/v2/portfolio/long-term-holdings";

    // Market info
    private static final String EP_MARKET_HOLIDAYS = "/v2/market/holidays";


    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ObjectMapper mapper;

    private final String baseUrl = DEFAULT_BASE;

    @Value("${upstox.access-token}")
    private String accessTokenProvider;

    private final Duration timeout = DEFAULT_TIMEOUT;

    // -------------------- Orders --------------------

    public Result<PlaceOrderResponse> placeOrder(PlaceOrderRequest req) {
        try {
            Objects.requireNonNull(req, "req");
            req.validate();

            String url = baseUrl + EP_PLACE_ORDER_V3;
            String body = mapper.writeValueAsString(req.toUpstoxPayload());

            HttpRequest httpReq = baseJsonRequest(url).POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<OrderIds> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            if (!"success".equalsIgnoreCase(api.status)) return Result.fail("Upstox error: " + resp.body());

            return Result.ok(new PlaceOrderResponse(api.data != null ? api.data.orderIds : List.of(), api.metadata != null ? api.metadata.latency : null));
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    public Result<ModifyOrderResponse> modifyOrder(ModifyOrderRequest req) {
        try {
            Objects.requireNonNull(req, "req");
            String url = baseUrl + EP_MODIFY_ORDER_V3;
            String body = mapper.writeValueAsString(req.toUpstoxPayload());

            HttpRequest httpReq = baseJsonRequest(url).PUT(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<OrderId> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            if (!"success".equalsIgnoreCase(api.status)) return Result.fail("Upstox error: " + resp.body());

            return Result.ok(new ModifyOrderResponse(api.data != null ? api.data.orderId : null, api.metadata != null ? api.metadata.latency : null));
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    public Result<CancelOrderResponse> cancelOrder(String orderId) {
        try {
            if (orderId == null || orderId.isBlank()) return Result.fail("orderId required");

            String url = baseUrl + EP_CANCEL_ORDER_V3 + "?order_id=" + urlEncode(orderId);
            HttpRequest httpReq = baseJsonRequest(url).DELETE().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<OrderId> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            if (!"success".equalsIgnoreCase(api.status)) return Result.fail("Upstox error: " + resp.body());

            return Result.ok(new CancelOrderResponse(api.data != null ? api.data.orderId : null, api.metadata != null ? api.metadata.latency : null));
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    public Result<BrokerOrder> getOrder(String orderId) {
        try {
            if (orderId == null || orderId.isBlank()) return Result.fail("orderId required");

            String url = baseUrl + EP_GET_ORDER_DETAILS + "?order_id=" + urlEncode(orderId);
            HttpRequest httpReq = baseJsonRequest(url).GET().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<UpstoxOrder> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            if (!"success".equalsIgnoreCase(api.status)) return Result.fail("Upstox error: " + resp.body());

            return Result.ok(BrokerOrder.from(api.data));
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    public Result<List<BrokerOrder>> listOrders() {
        try {
            String url = baseUrl + EP_GET_ORDER_BOOK;
            HttpRequest httpReq = baseJsonRequest(url).GET().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<List<UpstoxOrder>> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            List<UpstoxOrder> orders = (api != null && api.data != null) ? api.data : List.of();

            List<BrokerOrder> out = new ArrayList<>(orders.size());
            for (UpstoxOrder o : orders) out.add(BrokerOrder.from(o));
            return Result.ok(out);
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    public Result<List<BrokerTrade>> listTradesToday() {
        try {
            String url = baseUrl + EP_GET_TRADES_TODAY;
            HttpRequest httpReq = baseJsonRequest(url).GET().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<List<UpstoxTrade>> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            List<UpstoxTrade> trades = (api != null && api.data != null) ? api.data : List.of();

            List<BrokerTrade> out = new ArrayList<>(trades.size());
            for (UpstoxTrade t : trades) out.add(BrokerTrade.from(t));
            return Result.ok(out);
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    // -------------------- Portfolio --------------------

    public Result<PortfolioSnapshot> getPortfolioSnapshot() {
        try {
            // Positions
            HttpRequest posReq = baseJsonRequest(baseUrl + EP_POSITIONS).GET().build();
            HttpResponse<String> posResp = http.send(posReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(posResp.statusCode()))
                return Result.fail("HTTP " + posResp.statusCode() + " (positions) - " + posResp.body());
            ApiResponse<List<UpstoxPosition>> posApi = mapper.readValue(posResp.body(), new TypeReference<>() {
            });

            // Holdings
            HttpRequest hReq = baseJsonRequest(baseUrl + EP_HOLDINGS).GET().build();
            HttpResponse<String> hResp = http.send(hReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(hResp.statusCode()))
                return Result.fail("HTTP " + hResp.statusCode() + " (holdings) - " + hResp.body());
            ApiResponse<List<UpstoxHolding>> hApi = mapper.readValue(hResp.body(), new TypeReference<>() {
            });

            return Result.ok(new PortfolioSnapshot(posApi != null && posApi.data != null ? posApi.data : List.of(), hApi != null && hApi.data != null ? hApi.data : List.of()));
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    // -------------------- Market Info --------------------

    public Result<List<MarketHoliday>> getMarketHolidays(Optional<String> yyyyDashMmDashDd) {
        try {
            String url = baseUrl + EP_MARKET_HOLIDAYS + yyyyDashMmDashDd.map(d -> "/" + urlEncode(d)).orElse("");
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json").GET().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return Result.fail("HTTP " + resp.statusCode() + " - " + resp.body());

            ApiResponse<List<MarketHoliday>> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            return Result.ok(api != null && api.data != null ? api.data : List.of());
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    // Add to: com.trade.frankenstein.trader.service.upstox.UpstoxClient

    public Result<List<UpstoxPosition>> getPositions() {
        try {
            String url = baseUrl + EP_POSITIONS;
            HttpRequest httpReq = baseJsonRequest(url).GET().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return httpFail("getPositions", resp);

            ApiResponse<List<UpstoxPosition>> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            List<UpstoxPosition> out = (api != null && api.data != null) ? api.data : List.of();
            return Result.ok(out);
        } catch (Exception e) {
            return Result.fail(e);
        }
    }

    public Result<List<UpstoxHolding>> getPortfolio() {
        try {
            String url = baseUrl + EP_HOLDINGS;
            HttpRequest httpReq = baseJsonRequest(url).GET().build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (isNot2xx(resp.statusCode())) return httpFail("getPortfolio", resp);

            ApiResponse<List<UpstoxHolding>> api = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            List<UpstoxHolding> out = (api != null && api.data != null) ? api.data : List.of();
            return Result.ok(out);
        } catch (Exception e) {
            return Result.fail(e);
        }
    }


    // -------------------- Helpers --------------------

    private HttpRequest.Builder baseJsonRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization", "Bearer " + accessTokenProvider).header("Accept", "application/json").header("Content-Type", "application/json");
    }

    private static boolean is2xx(int sc) {
        return sc >= 200 && sc < 300;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isNot2xx(int sc) {
        return sc < 200 || sc >= 300;
    }

    private static Result httpFail(String op, HttpResponse<String> resp) {
        String body = safeBody(resp.body());
        // helpful hint for auth issues
        if (resp.statusCode() == 401) {
            return Result.fail("AUTH_REQUIRED", "HTTP 401 on " + op + ": " + body);
        }
        return Result.fail("HTTP_" + resp.statusCode(), op + ": " + body);
    }

    private static String safeBody(String body) {
        return body == null ? "" : body;
    }

}
