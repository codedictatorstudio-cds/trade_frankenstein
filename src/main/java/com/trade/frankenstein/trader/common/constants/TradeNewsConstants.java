package com.trade.frankenstein.trader.common.constants;

import java.util.Arrays;
import java.util.List;

public interface TradeNewsConstants {

    // Was: trade.news.enabled
    boolean NEWS_ENABLED = true;

    // Was: trade.news.urls
    String[] NEWS_URLS = {
            "https://feeds.content.dowjones.io/public/rss/mw_topstories",
            "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
            "https://www.moneycontrol.com/rss/marketreports.xml"
    };

    // Was: trade.news.refresh-ms
    String NEWS_REFRESH_MS = "30000";

    // Was: trade.news.cache-ttl-minutes
    int NEWS_CACHE_TTL_MINUTES = 15;

    // Was: trade.news.health-ttl-minutes
    int NEWS_HEALTH_TTL_MINUTES = 30;

    // Was: trade.news.health-check-cron
    String NEWS_HEALTH_CHECK_CRON = "0 */10 9-16 ? * MON-FRI";

    // (Not in your list, but used by the job previously. Keep default.)
    String NEWS_CACHE_CLEAR_CRON = "0 0 */4 * * *";

    // Was: trade.news.market-hours-only
    boolean NEWS_MARKET_HOURS_ONLY = true;

    // Was: trade.news.market-timezone
    String NEWS_MARKET_TIMEZONE = "Asia/Kolkata";

    // Was: trade.news.allow-html-scrape
    boolean NEWS_ALLOW_HTML_SCRAPE = true;

    // Was: trade.news.allowed-content-types
    String[] NEWS_ALLOWED_CONTENT_TYPES = {
            "application/rss+xml,application/atom+xml,text/xml,text/html"
    };

    // Was: trade.news.broadcast
    boolean NEWS_BROADCAST = true;

    List<String> bullish = Arrays.asList("surge", "record high", "upbeat", "rally", "gains", "rise",
            "beat estimates", "positive", "bullish", "momentum", "buying", "breakout", "outperform");

    List<String> bearish = Arrays.asList("plunge", "selloff", "downbeat", "fall", "losses", "decline",
            "miss estimates", "negative", "bearish", "slump", "crash", "selling", "fear", "underperform");

    String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
}
