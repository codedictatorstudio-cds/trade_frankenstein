package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.model.upstox.*;
import com.trade.frankenstein.trader.service.UpstoxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/upstox")
public class UpstoxController {

    @Autowired
    private UpstoxService upstoxService;

    @PostMapping("/order")
    public PlaceOrderResponse placeOrder(@RequestBody PlaceOrderRequest request) {
        return upstoxService.placeOrder(request);
    }

    @PostMapping("/order/modify")
    public ModifyOrderResponse modifyOrder(@RequestBody ModifyOrderRequest request) {
        return upstoxService.modifyOrder(request);
    }

    @DeleteMapping("/order")
    public CancelOrderResponse cancelOrder(@RequestParam String orderId) {
        return upstoxService.cancelOrder(orderId);
    }

    @GetMapping("/order")
    public OrderGetResponse getOrderDetails(@RequestParam String orderId) {
        return upstoxService.getOrderDetails(orderId);
    }

    @GetMapping("/order/history")
    public OrderHistoryResponse getOrderHistory(@RequestParam String order_id, @RequestParam String tag) {
        return upstoxService.getOrderHistory(order_id, tag);
    }

    @GetMapping("/orders")
    public List<OrderBookResponse> getOrderBook() {
        return upstoxService.getOrderBook();
    }

    @GetMapping("/trades/day")
    public OrderTradesResponse getTradesForDay() {
        return upstoxService.getTradesForDay();
    }

    @GetMapping("/trades")
    public OrderTradesResponse getOrderTrades() {
        return upstoxService.getOrderTrades();
    }

    @GetMapping("/portfolio/short-term")
    public PortfolioResponse getShortTermPositions() {
        return upstoxService.getShortTermPositions();
    }

    @GetMapping("/portfolio/long-term")
    public HoldingsResponse getLongTermHoldings() {
        return upstoxService.getLongTermHoldings();
    }

    @GetMapping("/market/ltp-quote")
    public LTP_Quotes getMarketLTPQuote(@RequestParam String instrument_key) {
        return upstoxService.getMarketLTPQuote(instrument_key);
    }

    @GetMapping("/market/ohlc-quote")
    public OHLC_Quotes getMarketOHLCQuote(@RequestParam String instrument_key, @RequestParam String interval) {
        return upstoxService.getMarketOHLCQuote(instrument_key, interval);
    }

    @GetMapping("/market/intraday-candle")
    public IntradayCandleResponse getIntradayCandleData(@RequestParam String instrument_key, @RequestParam String unit, @RequestParam String interval) {
        return upstoxService.getIntradayCandleData(instrument_key, unit, interval);
    }

    @GetMapping("/market/historical-candle")
    public HistoricalCandleResponse getHistoricalCandleData(@RequestParam String instrument_key, @RequestParam String unit, @RequestParam String interval, @RequestParam String to_date, @RequestParam String from_date) {
        return upstoxService.getHistoricalCandleData(instrument_key, unit, interval, to_date, from_date);
    }

    @PostMapping("/positions/exit-all")
    public OptionsExitResponse exitAllPositions(@RequestParam String segment, @RequestParam String tag) {
        return upstoxService.exitAllPositions(segment, tag);
    }

    @GetMapping("/funds")
    public FundsResponse getFundAndMargin(@RequestParam String segment) {
        return upstoxService.getFundAndMargin(segment);
    }

    @GetMapping("/market/holidays")
    public MarketHolidays getMarketHolidays(@RequestParam String date) {
        return upstoxService.getMarketHolidays(date);
    }

    @GetMapping("/market/data-feed")
    public MarketDataFeed getMarketDataFeed() {
        return upstoxService.getMarketDataFeed();
    }

    @GetMapping("/option/greeks")
    public OptionGreekResponse getOptionGreeks(@RequestParam String instrument_key) {
        return upstoxService.getOptionGreeks(instrument_key);
    }

    @GetMapping("/pnl/metadata")
    public PnLMetaDataResponse getPnLMetaData(@RequestParam String from_date, @RequestParam String to_date, @RequestParam String segment, @RequestParam String financial_year) {
        return upstoxService.getPnLMetaData(from_date, to_date, segment, financial_year);
    }

    @GetMapping("/pnl/reports")
    public PnLReportResponse getPnlReports(@RequestParam(required = false) String from_date, @RequestParam(required = false) String to_date, @RequestParam String segment, @RequestParam String financial_year, @RequestParam int page_number, @RequestParam int page_size) {
        return upstoxService.getPnlReports(from_date, to_date, segment, financial_year, page_number, page_size);
    }

    @GetMapping("/trade-charges")
    public TradeChargesResponse getTradeCharges(@RequestParam String from_date, @RequestParam String to_date, @RequestParam String segment, @RequestParam String financial_year) {
        return upstoxService.getTradeCharges(from_date, to_date, segment, financial_year);
    }

    @GetMapping("/profile")
    public ProfileResponse getUserProfile() {
        return upstoxService.getUserProfile();
    }

    @GetMapping("/option/instruments")
    public OptionsInstruments getOptionInstrument(@RequestParam String instrument_key, @RequestParam String expiry_date) {
        return upstoxService.getOptionInstrument(instrument_key, expiry_date);
    }
}

