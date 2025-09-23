package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.common.constants.UpstoxConstants;
import com.trade.frankenstein.trader.model.upstox.AuthenticationResponse;
import com.trade.frankenstein.trader.repo.upstox.AuthenticationResponseRepo;
import com.upstox.api.*;
import com.upstox.marketdatafeeder.rpc.proto.MarketDataFeed;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
@Slf4j
public class UpstoxService {

    @Autowired
    private RestTemplate template;

    @Autowired
    private UpstoxSessionToken upstoxSessionToken;

    @Autowired
    private UpstoxTradeMode tradeMode;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AuthenticationResponseRepo authenticationResponseRepo;

    private AuthenticationResponse authenticationResponse;

    private static final Set<String> WORKING_STATUSES = new HashSet<>(
            Arrays.asList("open", "queued", "validation pending", "trigger pending", "enquiry", "partially filled"));

    private static final int MAX_RETRIES = 5;
    private static final long BASE_BACKOFF_MS = 250, MAX_BACKOFF_MS = 4000;
    private final ConcurrentMap<String, GetOptionContractResponse> optionContractCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> optionContractCacheExpiry = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30000;

    @PostConstruct
    public void init() {
        this.authenticationResponse = authenticationResponseRepo.findAll().stream().findFirst().orElse(AuthenticationResponse.builder().build());
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkAndRefreshToken() {
        if (authenticationResponse.getResponse() == null || authenticationResponse.getCreatedDate() == null ||
                !checkCreatedDateOfToken(authenticationResponse.getCreatedDate())) {
            upstoxSessionToken.generateAccessToken();
            authenticationResponse = authenticationResponseRepo.findAll().stream().findFirst().get();
        }
    }

    private boolean checkCreatedDateOfToken(Instant createdDate) {
        LocalDate createdLocalDate = createdDate
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        LocalDate today = LocalDate.now();
        return createdLocalDate.equals(today);
    }

    /**
     * Generic wrapper: if a call throws 401, refresh token once and retry immediately.
     */
    private <T> T callWith401Refresh(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.info("401 from Upstox — refreshing token and retrying once...");
                upstoxSessionToken.generateAccessToken();
                authenticationResponse = authenticationResponseRepo.findAll().stream().findFirst().orElse(authenticationResponse);
                return call.get();
            }
            throw e;
        }
    }

    // =====================================================================================
    // Orders path — upstoxOrders (retry + circuit + rate limiter + bulkhead)
    // =====================================================================================

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "placeOrderFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
        log.info("Checking and refreshing token if needed : placeOrder");
        checkAndRefreshToken();

        String baseUrl = tradeMode.getTradeMode().equalsIgnoreCase(UpstoxConstants.TRADE_MODE_LIVE)
                ? UpstoxConstants.LIVE_URL
                : UpstoxConstants.SANDBOX_URL;
        String url = baseUrl + UpstoxConstants.PLACE_ORDER_URL;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<PlaceOrderRequest> entity = new HttpEntity<>(request, headers);
        log.info("Placing order with request: {}", request);

        PlaceOrderResponse response = callWith401Refresh(() ->
                template.postForObject(url, entity, PlaceOrderResponse.class));
        log.info("Order placed");
        return response;
    }

    public PlaceOrderResponse placeOrderFallback(PlaceOrderRequest request, Throwable ex) {
        log.warn("placeOrder fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (placeOrder)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "modifyOrderFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public ModifyOrderResponse modifyOrder(ModifyOrderRequest request) {
        log.info("Checking and refreshing token if needed : modifyOrder");
        checkAndRefreshToken();

        String baseUrl = tradeMode.getTradeMode().equalsIgnoreCase(UpstoxConstants.TRADE_MODE_LIVE)
                ? UpstoxConstants.LIVE_URL
                : UpstoxConstants.SANDBOX_URL;
        String url = baseUrl + UpstoxConstants.MODIFY_ORDER_URL;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<ModifyOrderRequest> entity = new HttpEntity<>(request, headers);
        log.info("Modifying order with request: {}", request);

        ModifyOrderResponse response = callWith401Refresh(() ->
                template.postForObject(url, entity, ModifyOrderResponse.class));
        log.info("Order modified");
        return response;
    }

    public ModifyOrderResponse modifyOrderFallback(ModifyOrderRequest request, Throwable ex) {
        log.warn("modifyOrder fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (modifyOrder)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "cancelOrderFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public CancelOrderResponse cancelOrder(String orderId) {
        log.info("Checking and refreshing token if needed : cancelOrder");
        checkAndRefreshToken();

        String baseUrl = tradeMode.getTradeMode().equalsIgnoreCase(UpstoxConstants.TRADE_MODE_LIVE)
                ? UpstoxConstants.LIVE_URL
                : UpstoxConstants.SANDBOX_URL;
        String url = baseUrl + UpstoxConstants.CANCEL_ORDER_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("order_id", orderId)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Cancelling order with request: {}", orderId);

        ResponseEntity<CancelOrderResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.DELETE, entity, CancelOrderResponse.class));
        log.info("Order cancelled");
        return response.getBody();
    }

    public CancelOrderResponse cancelOrderFallback(String orderId, Throwable ex) {
        log.warn("cancelOrder fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (cancelOrder)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "getOrderDetailsFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public GetOrderDetailsResponse getOrderDetails(String orderId) {
        log.info("Checking and refreshing token if needed : getOrder");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_ORDERS_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("order_id", orderId)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting order with request: {}", orderId);

        ResponseEntity<GetOrderDetailsResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetOrderDetailsResponse.class));
        log.info("Order details fetched");
        return response.getBody();
    }

    public GetOrderDetailsResponse getOrderDetailsFallback(String orderId, Throwable ex) {
        log.warn("getOrderDetails fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (getOrderDetails)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "getOrderHistoryFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public GetOrderResponse getOrderHistory(String order_id, String tag) {
        log.info("Checking and refreshing token if needed : getOrderHistory");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_ORDERS_HISTORY_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("order_id", Optional.of(order_id))
                .queryParamIfPresent("tag", Optional.of(tag))
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting order history");

        ResponseEntity<GetOrderResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetOrderResponse.class));
        log.info("Order history fetched");
        return response.getBody();
    }

    public GetOrderResponse getOrderHistoryFallback(String order_id, String tag, Throwable ex) {
        log.warn("getOrderHistory fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (getOrderHistory)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "getOrderBookFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public GetOrderBookResponse getOrderBook() {
        log.info("Checking and refreshing token if needed : getOrderBook");
        checkAndRefreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting order book");

        ResponseEntity<GetOrderBookResponse> response = callWith401Refresh(() ->
                template.exchange(UpstoxConstants.GET_ALL_ORDERS_URL, HttpMethod.GET, entity, GetOrderBookResponse.class));
        log.info("Order book fetched");
        return response.getBody();
    }

    public GetOrderBookResponse getOrderBookFallback(Throwable ex) {
        log.warn("getOrderBook fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (getOrderBook)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "getTradesForDayFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public GetTradeResponse getTradesForDay() {
        log.info("Checking and refreshing token if needed : getTradesForDay");
        checkAndRefreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting trades for the day");

        ResponseEntity<GetTradeResponse> response = callWith401Refresh(() ->
                template.exchange(UpstoxConstants.GET_TRADES_PER_DAY_URL, HttpMethod.GET, entity, GetTradeResponse.class));
        log.info("Trades for the day fetched");
        return response.getBody();
    }

    public GetTradeResponse getTradesForDayFallback(Throwable ex) {
        log.warn("getTradesForDay fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (getTradesForDay)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "getOrderTradesFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public GetTradeResponse getOrderTrades() {
        log.info("Checking and refreshing token if needed : getOrderTrades");
        checkAndRefreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting all order trades");

        ResponseEntity<GetTradeResponse> response = callWith401Refresh(() ->
                template.exchange(UpstoxConstants.GET_ORDER_TRADES_URL, HttpMethod.GET, entity, GetTradeResponse.class));
        log.info("All order trades fetched");
        return response.getBody();
    }

    public GetTradeResponse getOrderTradesFallback(Throwable ex) {
        log.warn("getOrderTrades fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (getOrderTrades)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "exitAllPositionsFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public CancelOrExitMultiOrderResponse exitAllPositions(String segment, String tag) {
        log.info("Checking and refreshing token if needed : exitAllPositions");
        checkAndRefreshToken();

        String url = UpstoxConstants.EXIT_ALL_ORDERS_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("segment", Optional.of(segment))
                .queryParamIfPresent("tag", Optional.of(tag))
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Exiting all positions for segment: {}", segment);

        ResponseEntity<CancelOrExitMultiOrderResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.POST, entity, CancelOrExitMultiOrderResponse.class));
        log.info("Exit all positions response fetched");
        return response.getBody();
    }

    public CancelOrExitMultiOrderResponse exitAllPositionsFallback(String segment, String tag, Throwable ex) {
        log.warn("exitAllPositions fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (exitAllPositions)", ex);
    }

    // Convenience wrappers stay under orders profile (since they call order endpoints)
    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "amendOrderPriceFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public ModifyOrderResponse amendOrderPrice(String orderId, Float newPrice) {
        log.info("Amending order price: orderId={}, newPrice={}", orderId, newPrice);
        checkAndRefreshToken();

        ModifyOrderRequest req = new ModifyOrderRequest();
        req.setOrderId(orderId);
        req.setPrice(newPrice);

        return modifyOrder(req);
    }

    public ModifyOrderResponse amendOrderPriceFallback(String orderId, Float newPrice, Throwable ex) {
        log.warn("amendOrderPrice fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (amendOrderPrice)", ex);
    }

    public boolean isOrderWorking(String orderId) {
        try {
            GetOrderDetailsResponse og = getOrderDetails(orderId);
            if (og == null || og.getData() == null || og.getData().getStatus() == null) return false;
            String s = og.getData().getStatus().trim().toLowerCase();
            boolean working = WORKING_STATUSES.contains(s);
            log.info("isOrderWorking({}) -> {} (status={})", orderId, working, s);
            return working;
        } catch (Exception e) {
            log.error("isOrderWorking({}) failed: {}", orderId, e);
            return false;
        }
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "placeTargetOrderFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public PlaceOrderResponse placeTargetOrder(String instrumentKey, int qty, Float targetPrice) {
        log.info("Placing target LIMIT order: {}, qty={}, price={}", instrumentKey, qty, targetPrice);
        checkAndRefreshToken();

        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setPrice(targetPrice);
        req.setInstrumentToken(instrumentKey);
        req.setQuantity(qty);
        req.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.SELL);
        req.setOrderType(PlaceOrderRequest.OrderTypeEnum.LIMIT);
        req.setProduct(PlaceOrderRequest.ProductEnum.I);
        req.setValidity(PlaceOrderRequest.ValidityEnum.DAY);

        return placeOrder(req);
    }

    public PlaceOrderResponse placeTargetOrderFallback(String instrumentKey, int qty, Float targetPrice, Throwable ex) {
        log.warn("placeTargetOrder fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (placeTargetOrder)", ex);
    }

    @Retry(name = "upstoxOrders")
    @CircuitBreaker(name = "upstoxOrders", fallbackMethod = "placeStopLossOrderFallback")
    @RateLimiter(name = "upstoxOrders")
    @Bulkhead(name = "upstoxOrders", type = Bulkhead.Type.SEMAPHORE)
    public PlaceOrderResponse placeStopLossOrder(String instrumentKey, int qty, Float triggerPrice) {
        log.info("Placing protective SL order: {}, qty={}, trigger={}", instrumentKey, qty, triggerPrice);
        checkAndRefreshToken();

        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setPrice(triggerPrice);
        req.setInstrumentToken(instrumentKey);
        req.setQuantity(qty);
        req.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.SELL);
        req.setOrderType(PlaceOrderRequest.OrderTypeEnum.SL);
        req.setProduct(PlaceOrderRequest.ProductEnum.I);
        req.setValidity(PlaceOrderRequest.ValidityEnum.DAY);

        return placeOrder(req);
    }

    public PlaceOrderResponse placeStopLossOrderFallback(String instrumentKey, int qty, Float triggerPrice, Throwable ex) {
        log.warn("placeStopLossOrder fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox orders path unavailable (placeStopLossOrder)", ex);
    }

    // =====================================================================================
    // Data/portfolio path — upstoxData
    // =====================================================================================

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getShortTermPositionsFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetPositionResponse getShortTermPositions() {
        log.info("Checking and refreshing token if needed : getPortfolio");
        checkAndRefreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting portfolio");

        ResponseEntity<GetPositionResponse> response = callWith401Refresh(() ->
                template.exchange(UpstoxConstants.GET_SHORT_TERM_POSITIONS_URL, HttpMethod.GET, entity, GetPositionResponse.class));
        log.info("Portfolio fetched");
        return response.getBody();
    }

    public GetPositionResponse getShortTermPositionsFallback(Throwable ex) {
        log.warn("getShortTermPositions fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getShortTermPositions)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getLongTermHoldingsFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetHoldingsResponse getLongTermHoldings() {
        log.info("Checking and refreshing token if needed : getLongTermHoldings");
        checkAndRefreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting long term holdings");

        ResponseEntity<GetHoldingsResponse> response = callWith401Refresh(() ->
                template.exchange(UpstoxConstants.GET_LONG_TERM_HOLDINGS_URL, HttpMethod.GET, entity, GetHoldingsResponse.class));
        log.info("Long term holdings fetched");
        return response.getBody();
    }

    public GetHoldingsResponse getLongTermHoldingsFallback(Throwable ex) {
        log.warn("getLongTermHoldings fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getLongTermHoldings)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getMarketLTPQuoteFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetMarketQuoteLastTradedPriceResponseV3 getMarketLTPQuote(String instrument_key) {
        log.info("Checking and refreshing token if needed : getMarketLTPQuote");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_MARKET_LTP_QUOTES_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("instrument_key", instrument_key)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting LTP quote for instrument key: {}", instrument_key);

        ResponseEntity<GetMarketQuoteLastTradedPriceResponseV3> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetMarketQuoteLastTradedPriceResponseV3.class));
        log.info("LTP quote fetched");
        return response.getBody();
    }

    public GetMarketQuoteLastTradedPriceResponseV3 getMarketLTPQuoteFallback(String instrument_key, Throwable ex) {
        log.warn("getMarketLTPQuote fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getMarketLTPQuote)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getMarketOHLCQuoteFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetMarketQuoteOHLCResponseV3 getMarketOHLCQuote(String instrument_key, String interval) {
        log.info("Checking and refreshing token if needed : getMarketOHLCQuote");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_MARKET_OHLC_QUOTES_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("instrument_key", instrument_key)
                .queryParam("interval", interval)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting OHLC quote for instrument key: {}", instrument_key);

        ResponseEntity<GetMarketQuoteOHLCResponseV3> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetMarketQuoteOHLCResponseV3.class));
        log.info("OHLC quote fetched");
        return response.getBody();
    }

    public GetMarketQuoteOHLCResponseV3 getMarketOHLCQuoteFallback(String instrument_key, String interval, Throwable ex) {
        log.warn("getMarketOHLCQuote fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getMarketOHLCQuote)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getIntradayCandleDataFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetIntraDayCandleResponse getIntradayCandleData(String instrument_key, String unit, String interval) {
        log.info("Checking and refreshing token if needed : getIntradayCandleData");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_HISTORICAL_INTRADAY_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .pathSegment(instrument_key)
                .pathSegment(unit)
                .pathSegment(interval)
                .build()
                .toUri();
        log.info("URI for Intraday Candle Data: {}", uri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting intraday candle data for instrument key: {}", instrument_key);

        ResponseEntity<GetIntraDayCandleResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetIntraDayCandleResponse.class));
        log.info("Intraday candle data fetched");
        return response.getBody();
    }

    public GetIntraDayCandleResponse getIntradayCandleDataFallback(String instrument_key, String unit, String interval, Throwable ex) {
        log.warn("getIntradayCandleData fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getIntradayCandleData)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getHistoricalCandleDataFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetHistoricalCandleResponse getHistoricalCandleData(String instrument_key, String unit, String interval, String to_date, String from_date) {
        log.info("Checking and refreshing token if needed : getHistoricalCandleData");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_HISTORICAL_CANDLE_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .pathSegment(instrument_key)
                .pathSegment(unit)
                .pathSegment(interval)
                .pathSegment(to_date)
                .pathSegment(from_date)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting historical candle data for instrument key: {}", instrument_key);

        ResponseEntity<GetHistoricalCandleResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetHistoricalCandleResponse.class));
        log.info("Historical candle data fetched");
        return response.getBody();
    }

    public GetHistoricalCandleResponse getHistoricalCandleDataFallback(String instrument_key, String unit, String interval, String to_date, String from_date, Throwable ex) {
        log.warn("getHistoricalCandleData fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getHistoricalCandleData)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getFundAndMarginFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetUserFundMarginResponse getFundAndMargin(String segment) {
        log.info("Checking and refreshing token if needed : getFundAndMargin");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_FUNDS_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("segment", Optional.of(segment))
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting funds and margin for segment: {}", segment);

        ResponseEntity<GetUserFundMarginResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetUserFundMarginResponse.class));
        log.info("Funds and margin fetched");
        return response.getBody();
    }

    public GetUserFundMarginResponse getFundAndMarginFallback(String segment, Throwable ex) {
        log.warn("getFundAndMargin fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getFundAndMargin)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getMarketHolidaysFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetHolidayResponse getMarketHolidays(String date) {
        log.info("Checking and refreshing token if needed : getMarketHolidays");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_MARKET_HOLIDAYS_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("date", Optional.of(date))
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting market holidays");

        ResponseEntity<GetHolidayResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetHolidayResponse.class));
        log.info("Market holidays fetched");
        return response.getBody();
    }

    public GetHolidayResponse getMarketHolidaysFallback(String date, Throwable ex) {
        log.warn("getMarketHolidays fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getMarketHolidays)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getMarketDataFeedFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public MarketDataFeed getMarketDataFeed() {
        log.info("getting market data feed url : getMarketDataFeed");
        String feedUrl = getMarketDataFeedUrl();

        log.info("Market Data Feed URL: {}", feedUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting market data feed details");

        ResponseEntity<MarketDataFeed> response = callWith401Refresh(() ->
                template.exchange(feedUrl, HttpMethod.GET, entity, MarketDataFeed.class));
        log.info("Market data feed details fetched");
        return response.getBody();
    }

    public MarketDataFeed getMarketDataFeedFallback(Throwable ex) {
        log.warn("getMarketDataFeed fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getMarketDataFeed)", ex);
    }

    /**
     * Private helper (used by getMarketDataFeed) — left unannotated.
     */
    private String getMarketDataFeedUrl() {
        log.info("Checking and refreshing token if needed : getMarketDataFeedUrl");
        checkAndRefreshToken();

        String url = UpstoxConstants.WEBSOCKET_MARKET_DATA_FEED_URL;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting market data feed URL : {}", url);
        ResponseEntity<JsonNode> response = callWith401Refresh(() ->
                template.exchange(url, HttpMethod.GET, entity, JsonNode.class));

        JsonNode node = response.getBody();
        log.info("Market data feed URL fetched", node);

        return node.get("data").get("authorized_redirect_uri").asText();
    }

    /**
     * Private helper — portfolio stream URL.
     */
    private String getPortfolioStreamFeedUrl(String update_types) {
        log.info("Checking and refreshing token if needed : getPortfolioStreamFeedUrl");
        checkAndRefreshToken();

        String url = UpstoxConstants.WEBSOCKET_PORTFOLIO_FEED_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("update_types", Optional.of(update_types))
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting market data feed URL");
        ResponseEntity<JsonNode> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, JsonNode.class));

        JsonNode node = response.getBody();
        log.info("Portfolio data feed URL fetched", node);

        return node.get("data").get("authorized_redirect_uri").asText();
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getOptionGreeksFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetMarketQuoteOptionGreekResponseV3 getOptionGreeks(String instrument_key) {
        log.info("Checking and refreshing token if needed : getOptionGreeks");
        checkAndRefreshToken();

        String url = UpstoxConstants.OPTION_GREEK_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("instrument_key", instrument_key)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting option greeks for instrument key: {}", instrument_key);

        ResponseEntity<GetMarketQuoteOptionGreekResponseV3> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetMarketQuoteOptionGreekResponseV3.class));
        log.info("Option greeks fetched");
        return response.getBody();
    }

    public GetMarketQuoteOptionGreekResponseV3 getOptionGreeksFallback(String instrument_key, Throwable ex) {
        log.warn("getOptionGreeks fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getOptionGreeks)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getPnLMetaDataFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetTradeWiseProfitAndLossMetaDataResponse getPnLMetaData(String from_date, String to_date, String segment, String financial_year) {
        log.info("Checking and refreshing token if needed : getPnLMetaData");
        checkAndRefreshToken();

        String url = UpstoxConstants.PNL_METADATA_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("from_date", Optional.of(from_date))
                .queryParamIfPresent("to_date", Optional.of(to_date))
                .queryParam("segment", segment)
                .queryParam("financial_year", financial_year)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting PnL Meta Data");

        ResponseEntity<GetTradeWiseProfitAndLossMetaDataResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetTradeWiseProfitAndLossMetaDataResponse.class));
        log.info("PnL Meta Data fetched");
        return response.getBody();
    }

    public GetTradeWiseProfitAndLossMetaDataResponse getPnLMetaDataFallback(String from_date, String to_date, String segment, String financial_year, Throwable ex) {
        log.warn("getPnLMetaData fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getPnLMetaData)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getPnlReportsFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetTradeWiseProfitAndLossDataResponse getPnlReports(String from_date, String to_date, String segment, String financial_year, int page_number, int page_size) {
        log.info("Checking and refreshing token if needed : getPnlReports");
        checkAndRefreshToken();

        String url = UpstoxConstants.PNL_REPORT_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("from_date", Optional.of(from_date))
                .queryParamIfPresent("to_date", Optional.of(to_date))
                .queryParam("segment", segment)
                .queryParam("financial_year", financial_year)
                .queryParam("page_number", page_number)
                .queryParam("page_size", page_size)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting PnL Reports");

        ResponseEntity<GetTradeWiseProfitAndLossDataResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetTradeWiseProfitAndLossDataResponse.class));
        log.info("PnL Reports fetched");
        return response.getBody();
    }

    public GetTradeWiseProfitAndLossDataResponse getPnlReportsFallback(String from_date, String to_date, String segment, String financial_year, int page_number, int page_size, Throwable ex) {
        log.warn("getPnlReports fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getPnlReports)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getTradeChargesFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetProfitAndLossChargesResponse getTradeCharges(String from_date, String to_date, String segment, String financial_year) {
        log.info("Checking and refreshing token if needed : getTradeCharges");
        checkAndRefreshToken();

        String url = UpstoxConstants.TRADE_CHARGES_URL;

        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParamIfPresent("from_date", Optional.of(from_date))
                .queryParamIfPresent("to_date", Optional.of(to_date))
                .queryParam("segment", segment)
                .queryParam("financial_year", financial_year)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting Trade Charges");

        ResponseEntity<GetProfitAndLossChargesResponse> response = callWith401Refresh(() ->
                template.exchange(uri, HttpMethod.GET, entity, GetProfitAndLossChargesResponse.class));
        log.info("Trade Charges fetched");
        return response.getBody();
    }

    public GetProfitAndLossChargesResponse getTradeChargesFallback(String from_date, String to_date, String segment, String financial_year, Throwable ex) {
        log.warn("getTradeCharges fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getTradeCharges)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getUserProfileFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetProfileResponse getUserProfile() {
        log.info("Checking and refreshing token if needed : getUserProfile");
        checkAndRefreshToken();

        String url = UpstoxConstants.PROFILE_URL;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting User Profile");

        ResponseEntity<GetProfileResponse> response = callWith401Refresh(() ->
                template.exchange(url, HttpMethod.GET, entity, GetProfileResponse.class));
        log.info("User Profile fetched");
        return response.getBody();
    }

    public GetProfileResponse getUserProfileFallback(Throwable ex) {
        log.warn("getUserProfile fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getUserProfile)", ex);
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getOptionInstrumentFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public GetOptionContractResponse getOptionInstrument(String instrument_key, String expiry_date) {
        log.info("Checking and refreshing token if needed : getOptionInstrument");
        checkAndRefreshToken();

        final String cacheKey = instrument_key + "|" + expiry_date;
        final Long exp = optionContractCacheExpiry.get(cacheKey);
        if (exp != null && exp > System.currentTimeMillis()) {
            GetOptionContractResponse hit = optionContractCache.get(cacheKey);
            if (hit != null) return hit;
        }

        String baseUrl = UpstoxConstants.GET_OPTIONS_CONTRACT_URL;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting option instrument for instrument key: {}, expiry: {}", instrument_key, expiry_date);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("instrument_key", instrument_key)
                .queryParamIfPresent("expiry_date", Optional.of(expiry_date))
                .build().toUri();

        int attempt = 0;
        while (true) {
            try {
                ResponseEntity<JsonNode> resp = callWith401Refresh(() ->
                        template.exchange(uri, HttpMethod.GET, entity, JsonNode.class));

                GetOptionContractResponse body = mapper.convertValue(resp.getBody(), GetOptionContractResponse.class);
                if (body != null) {
                    optionContractCache.put(cacheKey, body);
                    optionContractCacheExpiry.put(cacheKey, System.currentTimeMillis() + CACHE_TTL_MS);
                }
                return body;
            } catch (HttpClientErrorException.TooManyRequests e) {
                attempt++;
                if (attempt > MAX_RETRIES) throw e;
                String ra = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
                long waitMs = 0L;
                if (ra != null) {
                    try {
                        waitMs = Long.parseLong(ra.trim()) * 1000L;
                    } catch (Exception ignored) {
                    }
                }
                if (waitMs <= 0) {
                    long backoff = Math.min(MAX_BACKOFF_MS, (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1)));
                    waitMs = backoff + 100L;
                }
                sleepQuietly(waitMs);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                attempt++;
                if (attempt > MAX_RETRIES) throw e;
                long backoff = Math.min(MAX_BACKOFF_MS, (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1)));
                sleepQuietly(backoff + 100L);
            }
        }
    }

    public GetOptionContractResponse getOptionInstrumentFallback(String instrument_key, String expiry_date, Throwable ex) {
        log.warn("getOptionInstrument fallback due to {}", ex.toString());
        throw new RuntimeException("Upstox data path unavailable (getOptionInstrument)", ex);
    }

    public Float getRealizedPnlToday() {
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String d = today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            GetTradeWiseProfitAndLossDataResponse r = getPnlReports(d, d, "FO", getFinancialYear(), 1, 500);

            float sum = 0;
            if (r != null && r.getData() != null) {
                for (TradeWiseProfitAndLossData it : r.getData()) {
                    BigDecimal sell = BigDecimal.valueOf(it.getSellAmount());
                    BigDecimal buy = BigDecimal.valueOf(it.getBuyAmount());
                    sum = sell.subtract(buy).floatValue() + sum;
                }
            }

            log.info("Realized PnL for {}: {}", d, sum);
            return sum;
        } catch (Exception e) {
            log.error("getRealizedPnlToday failed: {}", e);
            return 0f;
        }
    }

    private String getFinancialYear() {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int nextYear = year + 1;
        return String.valueOf(year).substring(2) + String.valueOf(nextYear).substring(2);
    }

    /**
     * Best bid/ask snapshot for a single instrument.
     */
    public static class BestBidAsk {
        public final double bid;
        public final double ask;

        public BestBidAsk(double bid, double ask) {
            this.bid = bid;
            this.ask = ask;
        }
    }

    @Retry(name = "upstoxData")
    @CircuitBreaker(name = "upstoxData", fallbackMethod = "getBestBidAskFallback")
    @RateLimiter(name = "upstoxData")
    @Bulkhead(name = "upstoxData", type = Bulkhead.Type.SEMAPHORE)
    public Optional<BestBidAsk> getBestBidAsk(String instrumentKey) {
        try {
            log.info("Checking and refreshing token if needed : getBestBidAsk");
            checkAndRefreshToken();

            String url = "https://api.upstox.com/v2/market/quotes";

            URI uri = UriComponentsBuilder.fromUriString(url)
                    .queryParam("instrument_key", instrumentKey)
                    .queryParam("mode", "full")
                    .build()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            JsonNode root = callWith401Refresh(() -> {
                ResponseEntity<JsonNode> resp = template.exchange(uri, HttpMethod.GET, entity, JsonNode.class);
                return resp != null ? resp.getBody() : null;
            });
            if (root == null || root.get("data") == null) return Optional.empty();

            JsonNode node = root.get("data").get(instrumentKey);
            if (node == null) return Optional.empty();

            JsonNode buy0 = node.path("depth").path("buy").isArray() && node.path("depth").path("buy").size() > 0 ? node.path("depth").path("buy").get(0) : null;
            JsonNode sell0 = node.path("depth").path("sell").isArray() && node.path("depth").path("sell").size() > 0 ? node.path("depth").path("sell").get(0) : null;

            double bid = (buy0 != null && buy0.has("price")) ? buy0.get("price").asDouble() : 0.0;
            double ask = (sell0 != null && sell0.has("price")) ? sell0.get("price").asDouble() : 0.0;

            if (bid > 0.0 && ask > 0.0) return Optional.of(new BestBidAsk(bid, ask));
            return Optional.empty();
        } catch (Exception t) {
            return Optional.empty();
        }
    }

    public Optional<BestBidAsk> getBestBidAskFallback(String instrumentKey, Throwable ex) {
        log.warn("getBestBidAsk fallback due to {}", ex.toString());
        return Optional.empty();
    }
}
