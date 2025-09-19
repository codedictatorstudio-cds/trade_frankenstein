package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.frankenstein.trader.common.constants.UpstoxConstants;
import com.trade.frankenstein.trader.model.upstox.*;
import com.trade.frankenstein.trader.repo.upstox.AuthenticationResponseRepo;
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
    private final ConcurrentMap<String, OptionsInstruments> optionContractCache = new ConcurrentHashMap<>();
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

    public OrderGetResponse getOrderDetails(String orderId) {
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
        ResponseEntity<OrderGetResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, OrderGetResponse.class);

        log.info("Order details fetched");

        return response.getBody();
    }

    public OrderHistoryResponse getOrderHistory(String order_id, String tag) {
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
        ResponseEntity<OrderHistoryResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, OrderHistoryResponse.class);

        log.info("Order history fetched");

        return response.getBody();
    }

    public List<OrderBookResponse> getOrderBook() {
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
        ResponseEntity<OrderBookResponse[]> response = template.exchange(
                UpstoxConstants.GET_ALL_ORDERS_URL, HttpMethod.GET, entity, OrderBookResponse[].class);

        log.info("Order book fetched");

        // Convert the response body to a List
        return Arrays.asList(response.getBody());
    }

    public OrderTradesResponse getTradesForDay() {
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
        ResponseEntity<OrderTradesResponse> response = template.exchange(
                UpstoxConstants.GET_TRADES_PER_DAY_URL, HttpMethod.GET, entity, OrderTradesResponse.class);

        log.info("Trades for the day fetched");

        return response.getBody();
    }

    public OrderTradesResponse getOrderTrades() {
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
        ResponseEntity<OrderTradesResponse> response = template.exchange(
                UpstoxConstants.GET_ORDER_TRADES_URL, HttpMethod.GET, entity, OrderTradesResponse.class);

        log.info("All order trades fetched");

        return response.getBody();
    }

    public PortfolioResponse getShortTermPositions() {
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
        ResponseEntity<PortfolioResponse> response = template.exchange(
                UpstoxConstants.GET_SHORT_TERM_POSITIONS_URL, HttpMethod.GET, entity, PortfolioResponse.class);

        log.info("Portfolio fetched");

        return response.getBody();
    }

    public HoldingsResponse getLongTermHoldings() {
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
        ResponseEntity<HoldingsResponse> response = template.exchange(
                UpstoxConstants.GET_LONG_TERM_HOLDINGS_URL, HttpMethod.GET, entity, HoldingsResponse.class);

        log.info("Long term holdings fetched");

        return response.getBody();
    }

    public LTP_Quotes getMarketLTPQuote(String instrument_key) {
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
        ResponseEntity<LTP_Quotes> response = template.exchange(
                uri, HttpMethod.GET, entity, LTP_Quotes.class);

        log.info("LTP quote fetched");

        return response.getBody();
    }

    public OHLC_Quotes getMarketOHLCQuote(String instrument_key, String interval) {
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
        ResponseEntity<OHLC_Quotes> response = template.exchange(
                uri, HttpMethod.GET, entity, OHLC_Quotes.class);

        log.info("OHLC quote fetched");

        return response.getBody();
    }

    public IntradayCandleResponse getIntradayCandleData(String instrument_key, String unit, String interval) {
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
        ResponseEntity<IntradayCandleResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, IntradayCandleResponse.class);

        log.info("Intraday candle data fetched");

        return response.getBody();
    }

    public HistoricalCandleResponse getHistoricalCandleData(String instrument_key, String unit, String interval, String to_date, String from_date) {
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
        ResponseEntity<HistoricalCandleResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, HistoricalCandleResponse.class);

        log.info("Historical candle data fetched");

        return response.getBody();
    }

    public OptionsExitResponse exitAllPositions(String segment, String tag) {
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
        ResponseEntity<OptionsExitResponse> response = template.exchange(
                uri, HttpMethod.POST, entity, OptionsExitResponse.class);

        log.info("Exit all positions response fetched");

        return response.getBody();
    }

    public FundsResponse getFundAndMargin(String segment) {
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
        ResponseEntity<FundsResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, FundsResponse.class);

        log.info("Funds and margin fetched");

        return response.getBody();
    }

    public MarketHolidays getMarketHolidays(String date) {
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
        ResponseEntity<MarketHolidays> response = template.exchange(
                uri, HttpMethod.GET, entity, MarketHolidays.class);

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

    private PortfolioFeedResponse getPortfolioFeed(String update_types) {
        log.info("getting portfolio feed url : getPortfolioFeed");
        String feedUrl = getPortfolioStreamFeedUrl(update_types);

        log.info("Portfolio Feed URL: {}", feedUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(authenticationResponse.getResponse().getAccess_token());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        PortfolioFeedResponse response = new PortfolioFeedResponse();
        log.info("Getting portfolio feed details");

        if (update_types.equalsIgnoreCase("order")) {
            ResponseEntity<PortfolioFeedResponse.PortfolioFeedOrder> order = template.exchange(
                    feedUrl, HttpMethod.GET, entity, PortfolioFeedResponse.PortfolioFeedOrder.class);

            response.setOrder(order.getBody());
        } else if (update_types.equalsIgnoreCase("gtt_order")) {
            ResponseEntity<PortfolioFeedResponse.PortfolioFeedGTTOrder> gtt_order = template.exchange(
                    feedUrl, HttpMethod.GET, entity, PortfolioFeedResponse.PortfolioFeedGTTOrder.class);

            response.setGtt_order(gtt_order.getBody());
        } else if (update_types.equalsIgnoreCase("position")) {
            ResponseEntity<PortfolioFeedResponse.PortfolioFeedOrderPosition> position = template.exchange(
                    feedUrl, HttpMethod.GET, entity, PortfolioFeedResponse.PortfolioFeedOrderPosition.class);

            response.setPosition(position.getBody());
        } else if (update_types.equalsIgnoreCase("holding")) {
            ResponseEntity<PortfolioFeedResponse.PortfolioFeedOrderHolding> holding = template.exchange(
                    feedUrl, HttpMethod.GET, entity, PortfolioFeedResponse.PortfolioFeedOrderHolding.class);

            response.setHolding(holding.getBody());
        }


        log.info("Portfolio feed details fetched", response);
        return response;
    }

    public OptionGreekResponse getOptionGreeks(String instrument_key) {
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
        ResponseEntity<OptionGreekResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, OptionGreekResponse.class);

        log.info("Option greeks fetched");

        return response.getBody();
    }

    public PnLMetaDataResponse getPnLMetaData(String from_date, String to_date, String segment, String financial_year) {
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
        ResponseEntity<PnLMetaDataResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, PnLMetaDataResponse.class);

        log.info("PnL Meta Data fetched");

        return response.getBody();
    }

    public PnLReportResponse getPnlReports(String from_date, String to_date, String segment, String financial_year, int page_number, int page_size) {
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
        ResponseEntity<PnLReportResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, PnLReportResponse.class);

        log.info("PnL Reports fetched");

        return response.getBody();
    }

    public TradeChargesResponse getTradeCharges(String from_date, String to_date, String segment, String financial_year) {
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
        ResponseEntity<TradeChargesResponse> response = template.exchange(
                uri, HttpMethod.GET, entity, TradeChargesResponse.class);

        log.info("Trade Charges fetched");

        return response.getBody();
    }

    public ProfileResponse getUserProfile() {
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
        ResponseEntity<ProfileResponse> response = template.exchange(
                url, HttpMethod.GET, entity, ProfileResponse.class);

        log.info("User Profile fetched");

        return response.getBody();
    }

    public OptionsInstruments getOptionInstrument(String instrument_key, String expiry_date) {
        log.info("Checking and refreshing token if needed : getOptionInstrument");
        checkAndRefreshToken();

        final String cacheKey = instrument_key + "|" + expiry_date;
        final Long exp = optionContractCacheExpiry.get(cacheKey);
        if (exp != null && exp > System.currentTimeMillis()) {
            OptionsInstruments hit = optionContractCache.get(cacheKey);
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
                ResponseEntity<OptionsInstruments> resp = template.exchange(uri, HttpMethod.GET, entity, OptionsInstruments.class);
                OptionsInstruments body = resp.getBody();
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
    public ModifyOrderResponse amendOrderPrice(String orderId, BigDecimal newPrice) {
        log.info("Amending order price: orderId={}, newPrice={}", orderId, newPrice);
        checkAndRefreshToken();

        // Adjust field names to your ModifyOrderRequest (orderId vs order_id, price vs newPrice, etc.)
        ModifyOrderRequest req = ModifyOrderRequest.builder()
                .order_id(orderId)
                .price(newPrice.floatValue())
                .build();

        return modifyOrder(req);
    }

    // --- Convenience: quick status probe for engine trailing/SL chasing loops ---
    public boolean isOrderWorking(String orderId) {
        try {
            OrderGetResponse og = getOrderDetails(orderId);
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
    public PlaceOrderResponse placeTargetOrder(String instrumentKey, int qty, BigDecimal targetPrice) {
        log.info("Placing target LIMIT order: {}, qty={}, price={}", instrumentKey, qty, targetPrice);
        checkAndRefreshToken();

        // Adjust field names to your PlaceOrderRequest (transactionType/orderType/product/validity/etc.)
        PlaceOrderRequest req = PlaceOrderRequest.builder()
                .instrument_token(instrumentKey)
                .quantity(qty)
                .transaction_type("SELL")
                .order_type("LIMIT")
                .price(targetPrice)
                .product("I")       // intraday by default; change if you use another product
                .validity("DAY")
                .build();

        return placeOrder(req);
    }

    // --- Convenience: place a protective SL (SL or SL-L) sell for an existing long position ---
    public PlaceOrderResponse placeStopLossOrder(String instrumentKey, int qty, BigDecimal triggerPrice) {
        log.info("Placing protective SL order: {}, qty={}, trigger={}", instrumentKey, qty, triggerPrice);
        checkAndRefreshToken();

        // If you prefer SL-Market, set orderType("SL") and omit .price(...)
        PlaceOrderRequest req = PlaceOrderRequest.builder()
                .instrument_token(instrumentKey)
                .quantity(qty)
                .transaction_type("SELL")
                .order_type("SL")            // or "SL-L" if your API distinguishes
                .trigger_price(triggerPrice) // required for SL/SL-L
                .validity("DAY")
                .product("I")
                .build();

        return placeOrder(req);
    }

    private String getFinancialYear() {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int nextYear = year + 1;

        String financialYear = String.valueOf(year).substring(2) + String.valueOf(nextYear).substring(2);
        return financialYear;
    }

    public BigDecimal getRealizedPnlToday() {
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String d = today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")); // yyyy-MM-dd
            // Adjust this call's signature/args if your existing getPnlReports(...) differs
            PnLReportResponse r = getPnlReports(d, d, "FO", getFinancialYear(), 1, 500);

            BigDecimal sum = BigDecimal.ZERO;
            if (r != null && r.getData() != null) {
                for (PnLReportResponse.PnLReportItem it : r.getData()) {
                    // Realized PnL for a closed roundtrip row
                    BigDecimal sell = BigDecimal.valueOf(it.getSell_amount());
                    BigDecimal buy = BigDecimal.valueOf(it.getBuy_amount());
                    sum = sum.add(sell.subtract(buy));
                }
            }

            log.info("Realized PnL for {}: {}", d, sum);
            return sum;
        } catch (Exception e) {
            log.error("getRealizedPnlToday failed: {}", e);
            return BigDecimal.ZERO;
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
