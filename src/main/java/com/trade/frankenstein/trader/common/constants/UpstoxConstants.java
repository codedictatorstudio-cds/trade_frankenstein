package com.trade.frankenstein.trader.common.constants;

public interface UpstoxConstants {

    String API_BASE_URL_V2 = "https://api.upstox.com/v2";

    String API_BASE_URL_V3 = "https://api.upstox.com/v3";

    String AUTHORIZATION_URL = API_BASE_URL_V2 + "/login/authorization/dialog";

    String CLIENT_ID = "3e7d3f4e-a12b-4384-ae1f-f37a6aa73bc8";

    String SECRET_KEY = "2sue1sz85o";

    String ACCESS_TOKEN_URL = API_BASE_URL_V2 + "/login/oauth/token/request/:" + CLIENT_ID;

    String REDIRECT_URL = "http://localhost:8080/dashboard";

    String AUTH_URL = API_BASE_URL_V2 + "/login/authorization/token";

    String PROFILE_URL = API_BASE_URL_V2 + "/user/profile";

    String LIVE_URL = "https://api-hft.upstox.com/v3";

    String SANDBOX_URL = "https://api-sandbox.upstox.com/v3";

    String PLACE_ORDER_URL = "/order/place";

    String MODIFY_ORDER_URL = "/order/modify";

    String CANCEL_ORDER_URL = "/order/cancel";

    String GET_ORDERS_URL = API_BASE_URL_V2 + "/order/details";

    String GET_ALL_ORDERS_URL = API_BASE_URL_V2 + "/order/retrieve-all";

    String GET_ORDERS_HISTORY_URL = API_BASE_URL_V2 + "/order/history";

    String GET_TRADES_PER_DAY_URL = API_BASE_URL_V2 + "/order/trades/get-trades-for-day";

    String GET_ORDER_TRADES_URL = API_BASE_URL_V2 + "/order/trades";

    String GET_SHORT_TERM_POSITIONS_URL = API_BASE_URL_V2 + "/portfolio/short-term-positions";

    String GET_LONG_TERM_HOLDINGS_URL = API_BASE_URL_V2 + "/portfolio/long-term-holdings";

    String GET_MARKET_LTP_QUOTES_URL = API_BASE_URL_V3 + "/market-quote/ltp";

    String GET_MARKET_OHLC_QUOTES_URL = API_BASE_URL_V3 + "/market-quote/ohlc";

    String GET_HISTORICAL_INTRADAY_URL = API_BASE_URL_V3 + "/historical-candle/intraday/";

    String GET_HISTORICAL_CANDLE_URL = API_BASE_URL_V3 + "/historical-candle";

    String GET_OPTIONS_CONTRACT_URL = API_BASE_URL_V2 + "/option/contract";

    String EXIT_ALL_ORDERS_URL = API_BASE_URL_V2 + "/order/positions/exit";

    String GET_FUNDS_URL = API_BASE_URL_V2 + "/user/get-funds-and-margin";

    String GET_MARKET_HOLIDAYS_URL = API_BASE_URL_V2 + "/market/holidays";

    String WEBSOCKET_MARKET_DATA_FEED_URL = API_BASE_URL_V3 + "/feed/market-data-feed/authorize";

    String WEBSOCKET_PORTFOLIO_FEED_URL = API_BASE_URL_V2 + "/feed/portfolio-stream-feed/authorize";

    String OPTION_GREEK_URL = API_BASE_URL_V3 + "/market-quote/option-greek";

    String PNL_REPORT_URL = API_BASE_URL_V2 + "/trade/profit-loss/data";

    String TRADE_CHARGES_URL = API_BASE_URL_V2 + "/trade/profit-loss/charges";

    String PNL_METADATA_URL = API_BASE_URL_V2 + "/trade/profit-loss/metadata";

    String code_id = "code=";

    String response_type = "response_type=";

    String delimiter = "&";

    String ternary = "?";

    String client_id = "client_id=";

    String client_secret = "client_secret=";

    String redirect_uri = "redirect_uri=";

    String grant_type = "grant_type=authorization_code";

    String TRADE_MODE_SANDBOX = "SANDBOX";

    String TRADE_MODE_LIVE = "LIVE";

}
