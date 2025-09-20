package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

        boolean isToday = false;
        isToday = createdLocalDate.equals(today);

        return isToday;
    }

    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
        log.info("Checking and refreshing token if needed : placeOrder");
        checkAndRefreshToken();

        // Determine the base URL based on trade mode
        String baseUrl = tradeMode.getTradeMode().equalsIgnoreCase(UpstoxConstants.TRADE_MODE_LIVE)
                ? UpstoxConstants.LIVE_URL
                : UpstoxConstants.SANDBOX_URL;

        String url = baseUrl + UpstoxConstants.PLACE_ORDER_URL;

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with request and headers
        HttpEntity<PlaceOrderRequest> entity = new HttpEntity<>(request, headers);

        log.info("Placing order with request: {}", request);

        // Send the POST request and get the response
        PlaceOrderResponse response = template.postForObject(url, entity, PlaceOrderResponse.class);
        log.info("Order placed");

        return response;
    }

    public ModifyOrderResponse modifyOrder(ModifyOrderRequest request) {
        log.info("Checking and refreshing token if needed : modifyOrder");
        checkAndRefreshToken();

        // Determine the base URL based on trade mode
        String baseUrl = tradeMode.getTradeMode().equalsIgnoreCase(UpstoxConstants.TRADE_MODE_LIVE)
                ? UpstoxConstants.LIVE_URL
                : UpstoxConstants.SANDBOX_URL;

        String url = baseUrl + UpstoxConstants.MODIFY_ORDER_URL;

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with request and headers
        HttpEntity<ModifyOrderRequest> entity = new HttpEntity<>(request, headers);

        log.info("Modifying order with request: {}", request);

        // Send the POST request and get the response
        ModifyOrderResponse response = template.postForObject(url, entity, ModifyOrderResponse.class);
        log.info("Order modified");

        return response;
    }

    public CancelOrderResponse cancelOrder(String orderId) {
        log.info("Checking and refreshing token if needed : cancelOrder");
        checkAndRefreshToken();

        // Determine the base URL based on trade mode
        String baseUrl = tradeMode.getTradeMode().equalsIgnoreCase(UpstoxConstants.TRADE_MODE_LIVE)
                ? UpstoxConstants.LIVE_URL
                : UpstoxConstants.SANDBOX_URL;

        String url = baseUrl + UpstoxConstants.CANCEL_ORDER_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("order_id", orderId)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Cancelling order with request: {}", orderId);

        // Send the DELETE request and get the response
        ResponseEntity<CancelOrderResponse> response = template.exchange(
                uri, HttpMethod.DELETE, entity, CancelOrderResponse.class);

        log.info("Order cancelled");

        return response.getBody();
    }

    public GetOrderDetailsResponse getOrderDetails(String orderId) {
        log.info("Checking and refreshing token if needed : getOrder");
        checkAndRefreshToken();

        // Determine the base URL based on trade mode
        String url = UpstoxConstants.GET_ORDERS_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("order_id", orderId)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting order with request: {}", orderId);

        // Send the GET request and get the response
        ResponseEntity<GetOrderDetailsResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetOrderDetailsResponse.class);

        log.info("Order details fetched");

        return response.getBody();
    }

    public GetOrderResponse getOrderHistory(String order_id, String tag) {
        log.info("Checking and refreshing token if needed : getOrderHistory");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_ORDERS_HISTORY_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("order_id", Optional.of(order_id))
                .queryParamIfPresent("tag", Optional.of(tag))
                .build()
                .toUri();


        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting order history");
        // Send the GET request and get the response
        ResponseEntity<GetOrderResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetOrderResponse.class);

        log.info("Order history fetched");

        return response.getBody();
    }

    public GetOrderBookResponse getOrderBook() {
        log.info("Checking and refreshing token if needed : getOrderBook");
        checkAndRefreshToken();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting order book");

        // Send the GET request and get the response
        ResponseEntity<GetOrderBookResponse> response = template.exchange(
                UpstoxConstants.GET_ALL_ORDERS_URL, HttpMethod.GET, entity, GetOrderBookResponse.class);

        log.info("Order book fetched");

        // Convert the response body to a List
        return response.getBody();
    }

    public GetTradeResponse getTradesForDay() {
        log.info("Checking and refreshing token if needed : getTradesForDay");
        checkAndRefreshToken();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting trades for the day");

        // Send the GET request and get the response
        ResponseEntity<GetTradeResponse> response = template.exchange(
                UpstoxConstants.GET_TRADES_PER_DAY_URL, HttpMethod.GET, entity, GetTradeResponse.class);

        log.info("Trades for the day fetched");

        return response.getBody();
    }

    public GetTradeResponse getOrderTrades() {
        log.info("Checking and refreshing token if needed : getOrderTrades");
        checkAndRefreshToken();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting all order trades");

        // Send the GET request and get the response
        ResponseEntity<GetTradeResponse> response = template.exchange(
                UpstoxConstants.GET_ORDER_TRADES_URL, HttpMethod.GET, entity, GetTradeResponse.class);

        log.info("All order trades fetched");

        return response.getBody();
    }

    public GetPositionResponse getShortTermPositions() {
        log.info("Checking and refreshing token if needed : getPortfolio");
        checkAndRefreshToken();
        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting portfolio");

        // Send the GET request and get the response
        ResponseEntity<GetPositionResponse> response = template.exchange(
                UpstoxConstants.GET_SHORT_TERM_POSITIONS_URL, HttpMethod.GET, entity, GetPositionResponse.class);

        log.info("Portfolio fetched");

        return response.getBody();
    }

    public GetHoldingsResponse getLongTermHoldings() {
        log.info("Checking and refreshing token if needed : getLongTermHoldings");
        checkAndRefreshToken();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting long term holdings");

        // Send the GET request and get the response
        ResponseEntity<GetHoldingsResponse> response = template.exchange(
                UpstoxConstants.GET_LONG_TERM_HOLDINGS_URL, HttpMethod.GET, entity, GetHoldingsResponse.class);

        log.info("Long term holdings fetched");

        return response.getBody();
    }

    public GetMarketQuoteLastTradedPriceResponseV3 getMarketLTPQuote(String instrument_key) {
        log.info("Checking and refreshing token if needed : getMarketLTPQuote");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_MARKET_LTP_QUOTES_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("instrument_key", instrument_key)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting LTP quote for instrument key: {}", instrument_key);

        // Send the GET request and get the response
        ResponseEntity<GetMarketQuoteLastTradedPriceResponseV3> response = template.exchange(
                uri, HttpMethod.GET, entity, GetMarketQuoteLastTradedPriceResponseV3.class);

        log.info("LTP quote fetched");

        return response.getBody();
    }

    public GetMarketQuoteOHLCResponseV3 getMarketOHLCQuote(String instrument_key, String interval) {
        log.info("Checking and refreshing token if needed : getMarketOHLCQuote");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_MARKET_OHLC_QUOTES_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("instrument_key", instrument_key)
                .queryParam("interval", interval)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting OHLC quote for instrument key: {}", instrument_key);

        // Send the GET request and get the response
        ResponseEntity<GetMarketQuoteOHLCResponseV3> response = template.exchange(
                uri, HttpMethod.GET, entity, GetMarketQuoteOHLCResponseV3.class);

        log.info("OHLC quote fetched");

        return response.getBody();
    }

    public GetIntraDayCandleResponse getIntradayCandleData(String instrument_key, String unit, String interval) {
        log.info("Checking and refreshing token if needed : getIntradayCandleData");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_HISTORICAL_INTRADAY_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .pathSegment(instrument_key)
                .pathSegment(unit)
                .pathSegment(interval)
                .build()
                .toUri();
        log.info("URI for Intraday Candle Data: {}", uri);
        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting intraday candle data for instrument key: {}", instrument_key);
        // Send the GET request and get the response
        ResponseEntity<GetIntraDayCandleResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetIntraDayCandleResponse.class);

        log.info("Intraday candle data fetched");

        return response.getBody();
    }

    public GetHistoricalCandleResponse getHistoricalCandleData(String instrument_key, String unit, String interval, String to_date, String from_date) {
        log.info("Checking and refreshing token if needed : getHistoricalCandleData");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_HISTORICAL_CANDLE_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .pathSegment(instrument_key)
                .pathSegment(unit)
                .pathSegment(interval)
                .pathSegment(to_date)
                .pathSegment(from_date)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting historical candle data for instrument key: {}", instrument_key);

        // Send the GET request and get the response
        ResponseEntity<GetHistoricalCandleResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetHistoricalCandleResponse.class);

        log.info("Historical candle data fetched");

        return response.getBody();
    }

    public CancelOrExitMultiOrderResponse exitAllPositions(String segment, String tag) {
        log.info("Checking and refreshing token if needed : exitAllPositions");
        checkAndRefreshToken();

        String url = UpstoxConstants.EXIT_ALL_ORDERS_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("segment", Optional.of(segment))
                .queryParamIfPresent("tag", Optional.of(tag))
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Exiting all positions for segment: {}", segment);

        // Send the POST request and get the response
        ResponseEntity<CancelOrExitMultiOrderResponse> response = template.exchange(
                uri, HttpMethod.POST, entity, CancelOrExitMultiOrderResponse.class);

        log.info("Exit all positions response fetched");

        return response.getBody();
    }

    public GetUserFundMarginResponse getFundAndMargin(String segment) {
        log.info("Checking and refreshing token if needed : getFundAndMargin");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_FUNDS_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("segment", Optional.of(segment))
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting funds and margin for segment: {}", segment);

        // Send the GET request and get the response
        ResponseEntity<GetUserFundMarginResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetUserFundMarginResponse.class);

        log.info("Funds and margin fetched");

        return response.getBody();
    }

    public GetHolidayResponse getMarketHolidays(String date) {
        log.info("Checking and refreshing token if needed : getMarketHolidays");
        checkAndRefreshToken();

        String url = UpstoxConstants.GET_MARKET_HOLIDAYS_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("date", Optional.of(date))
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting market holidays");

        // Send the GET request and get the response
        ResponseEntity<GetHolidayResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetHolidayResponse.class);

        log.info("Market holidays fetched");

        return response.getBody();
    }

    private String getMarketDataFeedUrl() {
        log.info("Checking and refreshing token if needed : getMarketDataFeedUrl");
        checkAndRefreshToken();

        String url = UpstoxConstants.WEBSOCKET_MARKET_DATA_FEED_URL;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting market data feed URL : {}", url);
        ResponseEntity<JsonNode> response = template.exchange(
                url, HttpMethod.GET, entity, JsonNode.class);

        JsonNode node = response.getBody();
        log.info("Market data feed URL fetched", node);

        String feedUrl = node.get("data").get("authorized_redirect_uri").asText();

        return feedUrl;
    }

    public MarketDataFeed getMarketDataFeed() {
        log.info("getting market data feed url : getMarketDataFeed");
        String feedUrl = getMarketDataFeedUrl();

        log.info("Market Data Feed URL: {}", feedUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting market data feed details");
        ResponseEntity<MarketDataFeed> response = template.exchange(
                feedUrl, HttpMethod.GET, entity, MarketDataFeed.class);

        log.info("Market data feed details fetched");
        return response.getBody();
    }

    private String getPortfolioStreamFeedUrl(String update_types) {
        log.info("Checking and refreshing token if needed : getPortfolioStreamFeedUrl");
        checkAndRefreshToken();

        String url = UpstoxConstants.WEBSOCKET_PORTFOLIO_FEED_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("update_types", Optional.of(update_types))
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting market data feed URL");
        ResponseEntity<JsonNode> response = template.exchange(
                uri, HttpMethod.GET, entity, JsonNode.class);

        JsonNode node = response.getBody();
        log.info("Portfolio data feed URL fetched", node);

        String feedUrl = node.get("data").get("authorized_redirect_uri").asText();

        return feedUrl;
    }

    public GetMarketQuoteOptionGreekResponseV3 getOptionGreeks(String instrument_key) {
        log.info("Checking and refreshing token if needed : getOptionGreeks");
        checkAndRefreshToken();

        String url = UpstoxConstants.OPTION_GREEK_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("instrument_key", instrument_key)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting option greeks for instrument key: {}", instrument_key);

        // Send the GET request and get the response
        ResponseEntity<GetMarketQuoteOptionGreekResponseV3> response = template.exchange(
                uri, HttpMethod.GET, entity, GetMarketQuoteOptionGreekResponseV3.class);

        log.info("Option greeks fetched");

        return response.getBody();
    }

    public GetTradeWiseProfitAndLossMetaDataResponse getPnLMetaData(String from_date, String to_date, String segment, String financial_year) {
        log.info("Checking and refreshing token if needed : getPnLMetaData");
        checkAndRefreshToken();

        String url = UpstoxConstants.PNL_METADATA_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("from_date", Optional.of(from_date))
                .queryParamIfPresent("to_date", Optional.of(to_date))
                .queryParam("segment", segment)
                .queryParam("financial_year", financial_year)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting PnL Meta Data");

        // Send the GET request and get the response
        ResponseEntity<GetTradeWiseProfitAndLossMetaDataResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetTradeWiseProfitAndLossMetaDataResponse.class);

        log.info("PnL Meta Data fetched");

        return response.getBody();
    }

    public GetTradeWiseProfitAndLossDataResponse getPnlReports(String from_date, String to_date, String segment, String financial_year, int page_number, int page_size) {
        log.info("Checking and refreshing token if needed : getPnlReports");
        checkAndRefreshToken();

        String url = UpstoxConstants.PNL_REPORT_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("from_date", Optional.of(from_date))
                .queryParamIfPresent("to_date", Optional.of(to_date))
                .queryParam("segment", segment)
                .queryParam("financial_year", financial_year)
                .queryParam("page_number", page_number)
                .queryParam("page_size", page_size)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting PnL Reports");

        // Send the GET request and get the response
        ResponseEntity<GetTradeWiseProfitAndLossDataResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetTradeWiseProfitAndLossDataResponse.class);

        log.info("PnL Reports fetched");

        return response.getBody();
    }

    public GetProfitAndLossChargesResponse getTradeCharges(String from_date, String to_date, String segment, String financial_year) {
        log.info("Checking and refreshing token if needed : getTradeCharges");
        checkAndRefreshToken();

        String url = UpstoxConstants.TRADE_CHARGES_URL;

        URI uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParamIfPresent("from_date", Optional.of(from_date))
                .queryParamIfPresent("to_date", Optional.of(to_date))
                .queryParam("segment", segment)
                .queryParam("financial_year", financial_year)
                .build()
                .toUri();

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting Trade Charges");

        // Send the GET request and get the response
        ResponseEntity<GetProfitAndLossChargesResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, GetProfitAndLossChargesResponse.class);

        log.info("Trade Charges fetched");

        return response.getBody();
    }

    public GetProfileResponse getUserProfile() {
        log.info("Checking and refreshing token if needed : getUserProfile");
        checkAndRefreshToken();

        String url = UpstoxConstants.PROFILE_URL;

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Getting User Profile");

        // Send the GET request and get the response
        ResponseEntity<GetProfileResponse> response = template.exchange(
                url, HttpMethod.GET, entity, GetProfileResponse.class);

        log.info("User Profile fetched");

        return response.getBody();
    }

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

        // Set headers for the request
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        // Create the HTTP entity with headers
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Getting option instrument for instrument key: {}, expiry: {}", instrument_key, expiry_date);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("instrument_key", instrument_key)
                .queryParamIfPresent("expiry_date", Optional.of(expiry_date)) // never null
                .build().toUri();

        int attempt = 0;
        while (true) {
            try {
                ResponseEntity<GetOptionContractResponse> resp = template.exchange(uri, HttpMethod.GET, entity, GetOptionContractResponse.class);
                GetOptionContractResponse body = resp.getBody();
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
                    waitMs = backoff + 100L; // small jitter
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

    // --- Convenience: amend price on an existing order (wrapper on modifyOrder) ---
    public ModifyOrderResponse amendOrderPrice(String orderId, Float newPrice) {
        log.info("Amending order price: orderId={}, newPrice={}", orderId, newPrice);
        checkAndRefreshToken();

        // Adjust field names to your ModifyOrderRequest (orderId vs order_id, price vs newPrice, etc.)
        ModifyOrderRequest req = new ModifyOrderRequest();
        req.setOrderId(orderId);
        req.setPrice(newPrice);

        return modifyOrder(req);
    }

    // --- Convenience: quick status probe for engine trailing/SL chasing loops ---
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

    // --- Convenience: place a target LIMIT sell for an existing long position ---
    public PlaceOrderResponse placeTargetOrder(String instrumentKey, int qty, Float targetPrice) {
        log.info("Placing target LIMIT order: {}, qty={}, price={}", instrumentKey, qty, targetPrice);
        checkAndRefreshToken();

        // Adjust field names to your PlaceOrderRequest (transactionType/orderType/product/validity/etc.)
        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setPrice(targetPrice);
        req.setInstrumentToken(instrumentKey);
        req.setQuantity(qty);
        req.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.SELL);
        req.setOrderType(PlaceOrderRequest.OrderTypeEnum.LIMIT);
        req.setProduct(PlaceOrderRequest.ProductEnum.I); // intraday by default; change if you use another product
        req.setValidity(PlaceOrderRequest.ValidityEnum.DAY);

        return placeOrder(req);
    }

    // --- Convenience: place a protective SL (SL or SL-L) sell for an existing long position ---
    public PlaceOrderResponse placeStopLossOrder(String instrumentKey, int qty, Float triggerPrice) {
        log.info("Placing protective SL order: {}, qty={}, trigger={}", instrumentKey, qty, triggerPrice);
        checkAndRefreshToken();

        // If you prefer SL-Market, set orderType("SL") and omit .price(...)
        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setPrice(triggerPrice);
        req.setInstrumentToken(instrumentKey);
        req.setQuantity(qty);
        req.setTransactionType(PlaceOrderRequest.TransactionTypeEnum.SELL);
        req.setOrderType(PlaceOrderRequest.OrderTypeEnum.SL);
        req.setProduct(PlaceOrderRequest.ProductEnum.I); // intraday by default; change if you use another product
        req.setValidity(PlaceOrderRequest.ValidityEnum.DAY);

        return placeOrder(req);
    }

    private String getFinancialYear() {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int nextYear = year + 1;

        String financialYear = String.valueOf(year).substring(2) + String.valueOf(nextYear).substring(2);
        return financialYear;
    }

    public Float getRealizedPnlToday() {
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String d = today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")); // yyyy-MM-dd
            // Adjust this call's signature/args if your existing getPnlReports(...) differs
            GetTradeWiseProfitAndLossDataResponse r = getPnlReports(d, d, "FO", getFinancialYear(), 1, 500);

            float sum = 0;
            if (r != null && r.getData() != null) {
                for (TradeWiseProfitAndLossData it : r.getData()) {
                    // Realized PnL for a closed roundtrip row
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

    /**
     * Try FULL market quote for best bid/ask; falls back to empty if not available.
     */
    public Optional<BestBidAsk> getBestBidAsk(String instrumentKey) {
        try {
            log.info("Checking and refreshing token if needed : getBestBidAsk");
            checkAndRefreshToken();

            // Upstox FULL quote endpoint (depth in response)
            String url = "https://api.upstox.com/v2/market/quotes";

            URI uri = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("instrument_key", instrumentKey)
                    .queryParam("mode", "full")
                    .build()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<JsonNode> resp = template.exchange(uri, HttpMethod.GET, entity, JsonNode.class);
            JsonNode root = (resp != null) ? resp.getBody() : null;
            if (root == null || root.get("data") == null) return Optional.empty();

            JsonNode node = root.get("data").get(instrumentKey);
            if (node == null) return Optional.empty();

            // According to Upstox FULL schema: depth.buy/sell arrays (best at index 0)
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


}
