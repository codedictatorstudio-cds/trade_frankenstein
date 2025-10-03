package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.TradeNewsConstants;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.repo.documents.MarketSentimentSnapshotRepo;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Live-market news fetch + keyword sentiment with feed health preflight.
 * Java 8 compatible, no external libs.
 */
@Slf4j
@Service
public class NewsService {

    private static final int MAX_BUFFER_EVENTS = 2000; // ring buffer cap
    private final int defaultBurstWindowMin = 10;
    /**
     * In-memory rolling log of recent news events (append-only, auto-pruned).
     */
    private final Deque<NewsEvent> newsEvents = new ConcurrentLinkedDeque<>();
    // -------------------- Config --------------------
    private final List<String> feedUrls = Arrays.asList(TradeNewsConstants.NEWS_URLS);
    private final List<String> bullish = TradeNewsConstants.bullish;
    private final List<String> bearish = TradeNewsConstants.bearish;
    private final int minItemsForHighConfidence = 12;
    private final int maxItemsPerFeed = 30;
    private final int maxTotalItems = 300;
    // HTTP knobs
    private final int connectTimeoutMs = 15000;
    private final int readTimeoutMs = 15000;
    private final String userAgent = TradeNewsConstants.USER_AGENT;
    private final boolean allowHtmlScrape = true;
    private final boolean broadcastNewsUpdate = TradeNewsConstants.NEWS_BROADCAST;
    // Cache/health
    private final int cacheTtlMinutes = TradeNewsConstants.NEWS_CACHE_TTL_MINUTES;
    private final int healthTtlMinutes = TradeNewsConstants.NEWS_HEALTH_TTL_MINUTES;
    // -------------------- Caches --------------------
    private final Map<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private final Map<String, FeedHealth> feedHealth = new ConcurrentHashMap<>();
    private final List<String> allowedContentTypes = List.of(TradeNewsConstants.NEWS_ALLOWED_CONTENT_TYPES);
    @Autowired
    private MarketSentimentSnapshotRepo sentimentRepo;
    @Autowired
    private StreamGateway stream;

    // ===== Step-11 wiring (add-only) =====
    @Autowired(required = false)
    private MongoTemplate mongoTemplate; // used if present

    @Autowired(required = false)
    private EmbeddingClient embeddingClient; // optional embedder bean

    @Value("${tf.news.collection:news_articles}")
    private String newsCollection;

    // ===== Step-11 constants & field names (kept local to avoid touching other files) =====
    private static final String F_ID = "_id";
    private static final String F_TITLE = "title";
    private static final String F_DESC = "description";
    private static final String F_SOURCE = "source";
    private static final String F_PUBLISHED_AT = "publishedAt";
    private static final String F_URL = "url";
    private static final String F_EMBEDDING = "embedding";
    private static final String F_TOPIC = "topic";
    private static final String F_HASH = "contentHash";
    private static final String VECTOR_INDEX_NAME = "news_embedding_idx"; // Atlas Vector Search index

    private static final int EMBEDDING_DIM = 384;
    private static final double DEDUPE_SIMILARITY_THRESHOLD = 0.86d;
    private static final double CLUSTER_SIMILARITY_THRESHOLD = 0.80d;
    private static final int K_NEIGHBORS = 20;
    private static final int K_REFINE = 50;
    private static final int LINEAR_SCAN_LIMIT = 1000;
    private static final int MAX_TITLE_LEN = 400;
    private static final int MAX_DESC_LEN = 2000;

    private static List<String> trim(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private static String dedupeKey(Headline h) {
        String k = (h.getLink() != null && !h.getLink().isEmpty())
                ? h.getLink()
                : (h.getSource() + "::" + h.getTitle());
        return normalize(k);
    }

    // -------------------- Public API --------------------

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static int hits(NewsItem item, List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        String text = (item.title + " " + item.description).toLowerCase(Locale.ROOT);
        int c = 0;
        for (String k : keys) {
            String kw = k.trim().toLowerCase(Locale.ROOT);
            if (kw.isEmpty()) continue;
            int idx = 0;
            while ((idx = text.indexOf(kw, idx)) != -1) {
                c++;
                idx += kw.length();
            }
        }
        return c;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static byte[] readUpTo(InputStream is, int max) throws Exception {
        byte[] buf = new byte[Math.max(1, max)];
        int pos = 0;
        while (pos < max) {
            int r = is.read(buf, pos, max - pos);
            if (r <= 0) break;
            pos += r;
        }
        if (pos == buf.length) return buf;
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    // -------------------- Internals --------------------

    // ---- Parse helpers ----
    private static boolean looksLikeXml(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("<?xml") || t.startsWith("<rss") || t.startsWith("<feed");
    }

    private static List<NewsItem> parseRssOrAtom(String xml, String sourceUrl, int maxItems) throws Exception {
        List<NewsItem> items = new ArrayList<NewsItem>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        org.w3c.dom.Document doc = db.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        org.w3c.dom.NodeList rss = doc.getElementsByTagName("item");
        if (rss != null && rss.getLength() > 0) {
            int lim = Math.min(rss.getLength(), maxItems);
            for (int i = 0; i < lim; i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) rss.item(i);
                String title = text(e, "title");
                String desc = text(e, "description");
                String link = text(e, "link");
                String pub = text(e, "pubDate");
                Instant ts = parseRfc1123(pub);
                items.add(new NewsItem(title, desc, link, ts, hostOf(sourceUrl)));
            }
            return items;
        }

        org.w3c.dom.NodeList atom = doc.getElementsByTagName("entry");
        int lim = Math.min(atom.getLength(), maxItems);
        for (int i = 0; i < lim; i++) {
            org.w3c.dom.Element e = (org.w3c.dom.Element) atom.item(i);
            String title = text(e, "title");
            String desc = text(e, "summary");
            if (desc.isEmpty()) desc = text(e, "content");
            String link = attrOf(e, "link", "href");
            String updated = text(e, "updated");
            if (updated.isEmpty()) updated = text(e, "published");
            Instant ts = parseIsoInstant(updated);
            items.add(new NewsItem(title, desc, link, ts, hostOf(sourceUrl)));
        }
        return items;
    }

    private static Instant parseRfc1123(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant parseIsoInstant(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception ignored) {
            try {
                return ZonedDateTime.parse(s.trim()).toInstant();
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<NewsItem> parseHtmlBasic(String pageUrl, String html, int maxItems) {
        List<NewsItem> out = new ArrayList<NewsItem>();
        String source = hostOf(pageUrl);

        String ogTitle = metaContent(html, "(?i)<meta\\s+property\\s*=\\s*\"og:title\"\\s+content\\s*=\\s*\"([^\"]+)\"");
        String ogDesc = metaContent(html, "(?i)<meta\\s+property\\s*=\\s*\"og:description\"\\s+content\\s*=\\s*\"([^\"]+)\"");
        if (ogTitle == null || ogTitle.trim().isEmpty()) ogTitle = extractTagText(html, "title");
        String pageDesc = (ogDesc != null && !ogDesc.isEmpty()) ? ogDesc : metaContent(html, "(?i)<meta\\s+name\\s*=\\s*\"description\"\\s+content\\s*=\\s*\"([^\"]+)\"");
        if (ogTitle != null && !ogTitle.trim().isEmpty()) {
            out.add(new NewsItem(ogTitle.trim(), nvl(pageDesc), pageUrl, null, source));
        }

        try {
            Pattern headingPattern = Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>");
            Matcher hm = headingPattern.matcher(html);
            while (hm.find() && out.size() < maxItems) {
                String heading = stripTags(hm.group(1)).trim();
                if (heading.length() > 20 && heading.length() < 150) {
                    String nearbyHtml = html.substring(Math.max(0, hm.start() - 100), Math.min(html.length(), hm.end() + 100));
                    Pattern linkPattern = Pattern.compile("(?is)<a\\s+[^>]*href=['\"]([^'\"]+)['\"][^>]*>");
                    Matcher lm = linkPattern.matcher(nearbyHtml);
                    if (lm.find()) {
                        String href = lm.group(1);
                        try {
                            String fullUrl = resolve(new URI(pageUrl), href);
                            out.add(new NewsItem(heading, "", fullUrl, null, source));
                        } catch (Exception e) {
                            // skip
                        }
                    } else {
                        out.add(new NewsItem(heading, "", pageUrl, null, source));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Pattern aTag = Pattern.compile("(?is)<a\\s+([^>]+)>(.*?)</a>");
        Matcher m = aTag.matcher(html);
        Set<String> seen = new HashSet<String>();
        try {
            URI base = new URI(pageUrl);
            int guard = 0;
            while (m.find()) {
                if (out.size() >= maxItems) break;
                if (++guard > 8000) break;

                String attrs = m.group(1);
                String text = stripTags(m.group(2)).trim();
                if (text.length() < 20 || text.length() > 150) continue;

                String href = attrValue(attrs, "href");
                if (href == null || href.trim().isEmpty()) continue;
                if (href.startsWith("javascript:")) continue;

                String abs = resolve(base, href.trim());
                String key = normalize(text);
                if (seen.contains(key)) continue;
                seen.add(key);

                out.add(new NewsItem(text, "", abs, null, source));
            }
        } catch (Exception ignored) {
        }

        if (out.size() > maxItems) return out.subList(0, Math.max(1, maxItems));
        return out;
    }

    private static String resolve(URI base, String href) {
        try {
            URI rel = new URI(href);
            return base.resolve(rel).toString();
        } catch (Exception ignored) {
            return href;
        }
    }

    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String metaContent(String html, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String attrValue(String attrs, String name) {
        Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String text(org.w3c.dom.Element p, String tag) {
        org.w3c.dom.NodeList nl = p.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        org.w3c.dom.Node n = nl.item(0);
        return (n == null) ? "" : n.getTextContent();
    }

    private static String attrOf(org.w3c.dom.Element parent, String tag, String attr) {
        org.w3c.dom.NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        org.w3c.dom.Element el = (org.w3c.dom.Element) nl.item(0);
        String v = el.getAttribute(attr);
        return v == null ? "" : v;
    }

    private static String nvl(String s) {
        return (s == null) ? "" : s;
    }

    private static String extractTagText(String html, String tag) {
        if (html == null || tag == null || tag.trim().isEmpty()) return "";
        String regex = "(?is)<" + Pattern.quote(tag) + "\\b[^>]*>(.*?)</" + Pattern.quote(tag) + "\\s*>";
        Matcher m = Pattern.compile(regex).matcher(html);
        if (m.find()) {
            String inner = m.group(1);
            String text = stripTags(inner).replaceAll("\\s+", " ").trim();
            return htmlDecode(text);
        }
        return "";
    }

    private static String htmlDecode(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        try {
            URI uri = new URI(u);
            String host = uri.getHost();
            String path = uri.getPath();
            if (path == null) path = "";
            if (path.length() > 40) path = path.substring(0, 40) + "…";
            return (host == null ? "" : host) + path;
        } catch (Exception ignore) {
            return u;
        }
    }

    // -------------- Lifecycle / Preflight -----------
    @PostConstruct
    public void init() {
        try {
            preflightValidateFeeds(); // warm health map at startup
        } catch (Exception t) {
            log.error("Preflight validate failed: {}", t.getMessage());
        }
    }

    /**
     * Validate configured feeds (lightweight probe) and record health.
     * Safe to call periodically.
     */
    public void preflightValidateFeeds() {
        List<String> urls = (feedUrls == null) ? Collections.emptyList() : trim(feedUrls);
        if (urls.isEmpty()) {
            log.info("NEWS: No feeds configured (trade.news.urls empty)");
            return;
        }
        for (String url : urls) {
            try {
                FeedHealth h = probe(url);
                feedHealth.put(url, h);
                log.info("NEWS FEED HEALTH [{}]: ok={}, code={}, type={}, size~{}B, xmlLike={}, latencyMs={}, finalUrl={}",
                        shortUrl(url), Boolean.valueOf(h.isOk()), Integer.valueOf(h.getHttpCode()),
                        safe(h.getContentType()), Long.valueOf(h.getApproxBytes()),
                        Boolean.valueOf(h.isXmlLike()), Long.valueOf(h.getLatencyMs()), safe(h.getFinalUrl()));
            } catch (Exception e) {
                FeedHealth h = new FeedHealth();
                h.setOk(false);
                h.setError(e.getMessage());
                h.setLastChecked(Instant.now());
                feedHealth.put(url, h);
                log.error("NEWS FEED HEALTH [{}]: ERROR {}", shortUrl(url), e);
            }
        }
    }

    @Transactional(readOnly = true)
    public Result<List<Headline>> readAllConfigured() {
        try {
            List<String> urls = (feedUrls == null) ? Collections.emptyList() : trim(feedUrls);
            if (urls.isEmpty()) return Result.fail("NEWS_DISABLED_OR_NO_FEEDS");

            Map<String, Headline> byKey = new LinkedHashMap<String, Headline>();
            int errorCount = 0;
            int skippedUnhealthy = 0;

            for (String u : urls) {
                if (isRecentlyUnhealthy(u)) {
                    skippedUnhealthy++;
                    continue;
                }
                try {
                    List<NewsItem> items = fetchAnyWithRetry(u, maxItemsPerFeed);
                    for (NewsItem it : items) {
                        Headline h = it.toHeadline();
                        String key = dedupeKey(h);
                        if (!byKey.containsKey(key)) {
                            byKey.put(key, h);
                            if (byKey.size() >= maxTotalItems) break;
                        }
                    }
                    if (byKey.size() >= maxTotalItems) break;
                    log.info("Fetched {} items from {}", Integer.valueOf(items.size()), u);
                    markHealthy(u);
                } catch (Exception ex) {
                    errorCount++;
                    markUnhealthy(u, ex);
                    log.error("News fetch failed ({}): {}", u, ex);
                }
            }

            List<Headline> out = new ArrayList<Headline>(byKey.values());
            if (broadcastNewsUpdate) {
                try {
                    Map<String, Object> payload = new HashMap<String, Object>();
                    payload.put("asOf", Instant.now().toString());
                    payload.put("count", Integer.valueOf(out.size()));
                    payload.put("sources", Integer.valueOf(urls.size()));
                    payload.put("errors", Integer.valueOf(errorCount));
                    payload.put("skippedUnhealthy", Integer.valueOf(skippedUnhealthy));
                    stream.send("news.update", payload);
                } catch (Exception t) {
                    log.error("Broadcast news.update failed: {}", t);
                }
            }
            return out.isEmpty() ? Result.fail("NO_NEWS_ITEMS") : Result.ok(out);
        } catch (Exception e) {
            log.error("NEWS_READ_FAILED", e);
            return Result.fail("NEWS_READ_FAILED: " + e.getMessage());
        }
    }

    @Transactional
    public Result<MarketSentimentSnapshot> ingestAndUpdateSentiment() {
        try {
            List<String> urls = (feedUrls == null) ? Collections.emptyList() : trim(feedUrls);
            if (urls.isEmpty()) return Result.fail("NEWS_DISABLED_OR_NO_FEEDS");

            int bull = 0, bear = 0, total = 0, successfulFeeds = 0, skippedUnhealthy = 0;

            for (String u : urls) {
                if (isRecentlyUnhealthy(u)) {
                    skippedUnhealthy++;
                    continue;
                }
                try {
                    List<NewsItem> items = fetchAnyWithRetry(u, maxItemsPerFeed);
                    if (!items.isEmpty()) {
                        successfulFeeds++;

                        // ===== Step-11 injection (non-disruptive): persist + ANN dedupe bookkeeping =====
                        for (NewsItem it : items) {
                            try {
                                Headline hh = it.toHeadline();
                                processStep11(hh); // best-effort; does not alter sentiment counting
                            } catch (Throwable t) {
                                log.debug("NEWS_STEP11_PROCESS_ERROR {}", t.toString());
                            }
                        }

                        // === existing business logic (unchanged) ===
                        for (NewsItem it : items) {
                            bull += hits(it, bullish);
                            bear += hits(it, bearish);
                        }
                        total += items.size();
                    }
                    markHealthy(u);
                } catch (Exception ex) {
                    markUnhealthy(u, ex);
                    log.error("Feed fetch failed ({}): {}", u, ex);
                }
            }

            double raw = 0.0;
            if (bull + bear > 0) {
                raw = (bull - bear) / (double) (bull + bear);
                raw = clamp(raw, -1.0, 1.0);
            }
            int score = (int) Math.round(50.0 + raw * 50.0);

            int conf;
            if (successfulFeeds >= 3 && total >= minItemsForHighConfidence) {
                conf = 80 + Math.min(20, (total - minItemsForHighConfidence) / 5);
            } else if (successfulFeeds >= 1) {
                conf = 40 + Math.min(40, (successfulFeeds * 10) + (total * 2));
            } else {
                conf = 10;
            }
            conf = Math.max(0, Math.min(100, conf));

            MarketSentimentSnapshot snap = new MarketSentimentSnapshot();
            snap.setAsOf(Instant.now());
            snap.setSentiment(String.valueOf(score));
            snap.setConfidence(conf);

            log.info("Sentiment update: score={}, confidence={}, bull={}, bear={}, items={}, feeds={}, skippedUnhealthy={}",
                    Integer.valueOf(score), Integer.valueOf(conf), Integer.valueOf(bull), Integer.valueOf(bear),
                    Integer.valueOf(total), Integer.valueOf(successfulFeeds), Integer.valueOf(skippedUnhealthy));

            MarketSentimentSnapshot saved = sentimentRepo.save(snap);
            try {
                Map<String, Object> streamData = new HashMap<String, Object>();
                streamData.put("entity", saved);
                streamData.put("bull", Integer.valueOf(bull));
                streamData.put("bear", Integer.valueOf(bear));
                streamData.put("total", Integer.valueOf(total));
                streamData.put("feeds", Integer.valueOf(successfulFeeds));
                stream.send("sentiment.update", streamData);
            } catch (Exception t) {
                log.error("WebSocket stream send failed: {}", t);
            }
            return Result.ok(saved);
        } catch (Exception e) {
            log.error("NEWS_INGEST_FAILED", e);
            return Result.fail("NEWS_INGEST_FAILED: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Result<MarketSentimentSnapshot> getLatestSentiment() {
        MarketSentimentSnapshot latest = sentimentRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);
        return (latest == null) ? Result.fail("NO_SENTIMENT_SNAPSHOT") : Result.ok(latest);
    }

    /**
     * Health snapshot (can expose via controller if desired).
     */
    public Map<String, FeedHealth> getFeedHealth() {
        return new LinkedHashMap<String, FeedHealth>(feedHealth);
    }

    private boolean isRecentlyUnhealthy(String url) {
        FeedHealth h = feedHealth.get(url);
        if (h == null) return false;
        if (h.isOk()) return false;
        Instant last = h.getLastChecked();
        if (last == null) return false;
        long ageMs = Math.abs(System.currentTimeMillis() - last.toEpochMilli());
        return ageMs < TimeUnit.MINUTES.toMillis(healthTtlMinutes);
    }

    private void markHealthy(String url) {
        FeedHealth h = feedHealth.get(url);
        if (h == null) h = new FeedHealth();
        h.setOk(true);
        h.setError(null);
        h.setLastChecked(Instant.now());
        feedHealth.put(url, h);
    }

    private void markUnhealthy(String url, Exception ex) {
        FeedHealth h = feedHealth.get(url);
        if (h == null) h = new FeedHealth();
        h.setOk(false);
        h.setError(ex == null ? "UNKNOWN" : ex.getMessage());
        h.setLastChecked(Instant.now());
        feedHealth.put(url, h);
    }

    // ---- Fetch with caching + special handlers ----
    private List<NewsItem> fetchAnyWithRetry(String url, int maxItems) throws Exception {
        String cacheKey = url.toLowerCase(Locale.ROOT);

        CachedResult cached = resultCache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
            log.debug("Cache hit for {}", url);
            return cached.items;
        }

        String lowercaseUrl = url.toLowerCase(Locale.ROOT);
        try {
            List<NewsItem> items;

            if (lowercaseUrl.contains("sebi.gov.in")) {
                items = fetchSebiNews(url, maxItems);
            } else if (lowercaseUrl.contains("moneycontrol.com")) {
                items = fetchMoneycontrolNews(url, maxItems);
            } else if (lowercaseUrl.contains("yahoo.com")) {
                items = fetchYahooFinanceNews(url, maxItems);
            } else {
                items = fetchAny(url, maxItems);
            }

            resultCache.put(cacheKey, new CachedResult(items));
            return items;
        } catch (Exception e) {
            log.error("Initial fetch failed for {}, trying alternate UA", url, e);
            String altUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0";
            String altReferer = "https://www.google.com/search?q=market+news";

            String body = httpGet(url, connectTimeoutMs, readTimeoutMs, altUserAgent, altReferer, true);
            List<NewsItem> items;
            if (looksLikeXml(body)) {
                items = parseRssOrAtom(body, url, maxItems);
            } else if (allowHtmlScrape) {
                items = parseHtmlBasic(url, body, maxItems);
            } else {
                items = Collections.emptyList();
            }
            resultCache.put(cacheKey, new CachedResult(items));
            return items;
        }
    }

    private List<NewsItem> fetchAny(String url, int maxItems) throws Exception {
        String body;
        try {
            body = httpGet(url, connectTimeoutMs, readTimeoutMs, userAgent, null, true);
        } catch (Exception e) {
            String altUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0 Safari/537.36";
            String referer = "https://www.google.com/";
            body = httpGet(url, connectTimeoutMs, readTimeoutMs, altUa, referer, true);
        }

        if (looksLikeXml(body)) return parseRssOrAtom(body, url, maxItems);
        if (allowHtmlScrape) return parseHtmlBasic(url, body, maxItems);
        log.debug("Fallback fetch returned non-XML and HTML scraping disabled: {}", url);
        return Collections.emptyList();
    }

    // ---- Special handlers ----
    private List<NewsItem> fetchSebiNews(String url, int maxItems) throws Exception {
        String body = httpGet(url, connectTimeoutMs, readTimeoutMs, userAgent, null, true);
        List<NewsItem> items = new ArrayList<NewsItem>();
        String source = "SEBI";

        Pattern newsPattern = Pattern.compile("<tr[^>]*>\\s*<td[^>]*>(.*?)</td>\\s*<td[^>]*>(.*?)</td>\\s*<td[^>]*>(.*?)</td>\\s*</tr>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = newsPattern.matcher(body);

        int count = 0;
        while (matcher.find() && count < maxItems) {
            try {
                String dateText = stripTags(matcher.group(1)).trim();
                String title = stripTags(matcher.group(2)).trim();
                String pdfLink = null;

                Matcher linkMatcher = Pattern.compile("href=['\"]([^'\"]+\\.pdf)['\"]", Pattern.CASE_INSENSITIVE)
                        .matcher(matcher.group(2));
                if (linkMatcher.find()) {
                    pdfLink = linkMatcher.group(1);
                    if (pdfLink.startsWith("/")) pdfLink = "https://www.sebi.gov.in" + pdfLink;
                }

                if (!title.isEmpty()) {
                    items.add(new NewsItem(title, "SEBI Notification: " + dateText,
                            pdfLink != null ? pdfLink : url, null, source));
                    count++;
                }
            } catch (Exception e) {
                log.error("Error parsing SEBI news item: {}", e);
            }
        }
        return items;
    }

    private List<NewsItem> fetchMoneycontrolNews(String url, int maxItems) throws Exception {
        String body = httpGet(url, connectTimeoutMs * 2, readTimeoutMs * 2,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "https://www.google.com/", true);

        List<NewsItem> items = new ArrayList<NewsItem>();
        String source = "Moneycontrol";

        Pattern cardPattern = Pattern.compile(
                "<div[^>]*class=['\"][^'\"]*card[^'\"]*['\"][^>]*>\\s*"
                        + "<a[^>]*href=['\"]([^'\"]+)['\"][^>]*>\\s*"
                        + "(?:.*?<h3[^>]*>(.*?)</h3>|.*?<div[^>]*class=['\"][^'\"]*headline[^'\"]*['\"][^>]*>(.*?)</div>)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher cardMatcher = cardPattern.matcher(body);
        int count = 0;

        while (cardMatcher.find() && count < maxItems) {
            try {
                String link = cardMatcher.group(1);
                String title = cardMatcher.group(2) != null ? cardMatcher.group(2) : cardMatcher.group(3);

                if (title != null) {
                    title = stripTags(title).trim();
                    if (!title.isEmpty() && link != null) {
                        if (!link.startsWith("http")) {
                            link = "https://www.moneycontrol.com" + (link.startsWith("/") ? link : "/" + link);
                        }
                        items.add(new NewsItem(title, "", link, null, source));
                        count++;
                    }
                }
            } catch (Exception e) {
                log.error("Error parsing Moneycontrol card: {}", e);
            }
        }

        if (items.isEmpty()) items = parseHtmlBasic(url, body, maxItems);
        return items;
    }

    private List<NewsItem> fetchYahooFinanceNews(String url, int maxItems) throws Exception {
        String body = httpGet(url, connectTimeoutMs, readTimeoutMs,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
                "https://www.google.com/", true);

        List<NewsItem> items = new ArrayList<NewsItem>();
        String source = "Yahoo Finance";

        Pattern newsPattern = Pattern.compile(
                "<div[^>]*class=['\"]Ov\\([^'\"]*\\)[^>]*>\\s*<div[^>]*>\\s*<h3[^>]*>\\s*<a[^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher newsMatcher = newsPattern.matcher(body);
        int count = 0;

        while (newsMatcher.find() && count < maxItems) {
            try {
                String link = newsMatcher.group(1);
                String title = stripTags(newsMatcher.group(2)).trim();

                if (!title.isEmpty()) {
                    if (!link.startsWith("http")) {
                        link = "https://sg.finance.yahoo.com" + (link.startsWith("/") ? link : "/" + link);
                    }
                    items.add(new NewsItem(title, "", link, null, source));
                    count++;
                }
            } catch (Exception e) {
                log.error("Error parsing Yahoo Finance news item: {}", e);
            }
        }
        if (items.isEmpty()) items = parseHtmlBasic(url, body, maxItems);
        return items;
    }

    // ---- Probe (health check) ----
    private FeedHealth probe(String url) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");                   // HEAD is often blocked; use light GET
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(Math.min(5000, connectTimeoutMs));
        c.setReadTimeout(Math.min(5000, readTimeoutMs));
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        c.setRequestProperty("Accept-Encoding", "gzip, deflate");
        c.setRequestProperty("Range", "bytes=0-4095"); // small sniff

        FeedHealth h = new FeedHealth();
        int code = c.getResponseCode();
        h.setHttpCode(code);
        h.setContentType(c.getContentType());
        h.setFinalUrl(c.getURL() != null ? c.getURL().toString() : url);
        h.setLastChecked(Instant.now());

        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            h.setOk(false);
            h.setError("HTTP " + code);
            h.setLatencyMs(System.currentTimeMillis() - t0);
            return h;
        }

        InputStream is = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) is = new GZIPInputStream(is);
        byte[] sniff = readUpTo(is, 4096);
        h.setApproxBytes(sniff.length);
        String s = new String(sniff, StandardCharsets.UTF_8);
        boolean xmlLike = looksLikeXml(s);
        h.setXmlLike(xmlLike);

        boolean typeAllowed = allowedContentTypes == null || allowedContentTypes.isEmpty()
                || (c.getContentType() != null && allowedContentTypes.contains(c.getContentType().toLowerCase(Locale.ROOT)));

        boolean htmlOk = allowHtmlScrape && s.toLowerCase(Locale.ROOT).contains("<html");
        h.setOk((xmlLike || htmlOk) && typeAllowed);
        h.setLatencyMs(System.currentTimeMillis() - t0);
        return h;
    }

    private String httpGet(String u, int ct, int rt, String ua, String referer, boolean handleGzip) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(ct);
        c.setReadTimeout(rt);
        c.setRequestProperty("User-Agent", ua == null ? "TradeFrankensteinBot/1.0" : ua);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        c.setRequestProperty("Cache-Control", "max-age=0");
        if (handleGzip) c.setRequestProperty("Accept-Encoding", "gzip, deflate");
        if (referer != null) c.setRequestProperty("Referer", referer);

        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            InputStream es = c.getErrorStream();
            if (es != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
                    for (int i = 0; i < 10; i++) {
                        if (br.readLine() == null) break;
                    }
                } catch (Exception ignored) {
                }
            }
            throw new IllegalStateException("HTTP " + code);
        }

        InputStream is = c.getInputStream();
        if (handleGzip && "gzip".equalsIgnoreCase(c.getContentEncoding())) {
            is = new GZIPInputStream(is);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    public void clearCache() {
        resultCache.clear();
    }

    public Optional<Integer> getRecentBurstCount(int minutes) {
        try {
            final int winMin = minutes > 0 ? minutes : Math.max(1, defaultBurstWindowMin);
            final Instant now = Instant.now();
            final Duration window = Duration.ofMinutes(winMin);

            // Purge old entries beyond a reasonable cap (>= window, up to 3 hours)
            final Duration purgeHorizon = window.compareTo(Duration.ofHours(3)) > 0 ? window : Duration.ofHours(3);
            while (true) {
                NewsEvent head = newsEvents.peekFirst();
                if (head == null) break;
                if (Duration.between(head.ts, now).compareTo(purgeHorizon) > 0) {
                    newsEvents.pollFirst();
                } else {
                    break;
                }
            }

            // Count events within the requested window (iterate newest→oldest; early break when older)
            int count = 0;
            for (Iterator<NewsEvent> it = newsEvents.descendingIterator(); it.hasNext(); ) {
                NewsEvent e = it.next();
                if (Duration.between(e.ts, now).compareTo(window) <= 0) {
                    count++;
                } else {
                    // older than window; since list is time-ordered, we can stop
                    break;
                }
            }
            return Optional.of(count);
        } catch (Exception t) {
            return Optional.of(0);
        }
    }

    /**
     * Call this from your NewsIngestJob when a news item is ingested.
     */
    public void recordNewsEvent(String source, String symbol, String category, Instant publishedAt) {
        Instant ts = publishedAt != null ? publishedAt : Instant.now();
        newsEvents.addLast(new NewsEvent(ts, source, symbol, category));
        // ring-buffer trim
        while (newsEvents.size() > MAX_BUFFER_EVENTS) newsEvents.pollFirst();
    }

    // -------------------- Models --------------------
    private static final class CachedResult {
        final List<NewsItem> items;
        final long timestamp;

        CachedResult(List<NewsItem> items) {
            this.items = items;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(int ttlMinutes) {
            return System.currentTimeMillis() - timestamp > TimeUnit.MINUTES.toMillis(ttlMinutes);
        }
    }

    @Getter
    @Setter
    public static class FeedHealth {
        private boolean ok;
        private int httpCode;
        private String contentType;
        private String finalUrl;
        private String error;
        private long latencyMs;
        private long approxBytes;
        private boolean xmlLike;
        private Instant lastChecked;
    }

    private record NewsItem(String title, String description, String link, Instant publishedAt, String source) {
        private NewsItem(String title, String description) {
            this(title, description, "", null, "");
        }

        private NewsItem(String title, String description, String link, Instant publishedAt, String source) {
            this.title = (title == null) ? "" : title;
            this.description = (description == null) ? "" : description;
            this.link = (link == null) ? "" : link;
            this.publishedAt = publishedAt;
            this.source = (source == null) ? "" : source;
        }

        Headline toHeadline() {
            Headline h = new Headline();
            h.setTitle(nvl(title));
            h.setDescription(nvl(description));
            h.setLink(nvl(link));
            h.setSource(nvl(source));
            h.setPublishedAt(publishedAt);
            return h;
        }
    }

    @Getter
    @Setter
    public static class Headline {
        private String title;
        private String description;
        private String link;
        private String source;
        private Instant publishedAt;
    }

    private record NewsEvent(Instant ts, String source, String symbol, String category) {
    }

    // ===================== Step-11 helpers (ADD-ONLY) =====================

    /**
     * Minimal embedder contract; implement and register as a Spring bean if you want embeddings.
     */
    public interface EmbeddingClient {
        double[] embed(String text);
    }

    private void processStep11(Headline h) {
        // Best-effort only: do nothing if Mongo or embedder are unavailable.
        if (mongoTemplate == null) return;
        try {
            String key = contentKeyForEmbeddings(h.getTitle(), h.getDescription());
            String contentHash = sha256Base64(key);

            // persist-only fast path if already seen
            if (existsByContentHash(contentHash)) {
                // Optional: still record event; no-op persist for now
                log.debug("NEWS_DEDUPE HASH_DUP {}", shortTitle(nvl(h.getTitle())));
                return;
            }

            double[] embedding = null;
            if (embeddingClient != null) {
                String text = (nvl(h.getTitle()) + " " + nvl(h.getDescription())).trim();
                embedding = embeddingClient.embed(text);
                if (embedding == null || embedding.length != EMBEDDING_DIM) {
                    log.warn("Embedding invalid dim={}, skipping ANN for this item.",
                            embedding == null ? -1 : embedding.length);
                    embedding = null;
                }
            }

            // Optional ANN dedupe (we don't skip counting; this is only for storage/labels)
            if (embedding != null) {
                double sim = nearestSimilarity(embedding);
                if (sim >= DEDUPE_SIMILARITY_THRESHOLD) {
                    log.debug("NEWS_DEDUPE ANN_DUP {} score={}", shortTitle(nvl(h.getTitle())),
                            String.format(java.util.Locale.US, "%.3f", sim));
                    // still persist for history? choose to persist with topic
                }
            }

            String topic = null;
            if (embedding != null) {
                double sim2 = nearestSimilarity(embedding);
                topic = (sim2 >= CLUSTER_SIMILARITY_THRESHOLD)
                        ? "topic-" + java.time.LocalDate.now()
                        : "topic-" + java.time.LocalDate.now() + "-new";
            }

            // Persist
            saveArticle(h, embedding, topic, contentHash);
        } catch (Throwable t) {
            log.debug("NEWS_STEP11_ERROR {}", t.toString());
        }
    }

    private String contentKeyForEmbeddings(String title, String desc) {
        String t = truncate(normalize(title), MAX_TITLE_LEN);
        String d = truncate(normalize(desc), MAX_DESC_LEN);
        return t + " | " + d;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String shortTitle(String t) {
        if (t == null) return "";
        return t.length() <= 64 ? t : t.substring(0, 61) + "...";
    }

    private String sha256Base64(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(dig);
        } catch (Exception e) {
            return "hash_err_" + s.hashCode();
        }
    }

    private boolean existsByContentHash(String hash) {
        try {
            Query q = new Query(Criteria.where(F_HASH).is(hash)).limit(1);
            return mongoTemplate.exists(q, newsCollection);
        } catch (Throwable t) {
            return false;
        }
    }

    private double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return -2.0d;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return -2.0d;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private double nearestSimilarity(double[] embedding) {
        if (embedding == null || embedding.length != EMBEDDING_DIM) return -2.0d;

        // Preferred: MongoDB Atlas Vector Search
        try {
            org.bson.Document vectorSearch = new org.bson.Document("$vectorSearch", new org.bson.Document()
                    .append("index", VECTOR_INDEX_NAME)
                    .append("path", F_EMBEDDING)
                    .append("queryVector", embedding)
                    .append("numCandidates", K_REFINE)
                    .append("limit", K_NEIGHBORS));

            Aggregation agg = Aggregation.newAggregation(
                    new AggregationOperation() {
                        @Override
                        public org.bson.Document toDocument(org.springframework.data.mongodb.core.aggregation.AggregationOperationContext context) {
                            return vectorSearch;
                        }
                    },
                    Aggregation.project(F_TITLE, F_DESC).andExpression("{ $meta: 'vectorSearchScore' }").as("score")
            );

            AggregationResults<Document> results = mongoTemplate.aggregate(agg, newsCollection, Document.class);
            double best = -2.0d;
            for (Document d : results.getMappedResults()) {
                Object sc = d.get("score");
                if (sc instanceof Number) {
                    double s = ((Number) sc).doubleValue();
                    if (s > best) best = s;
                }
            }
            if (best > -2.0d) return best;
        } catch (Throwable t) {
            log.debug("VectorSearch not available, linear scan fallback. {}", t.toString());
        }

        // Fallback: linear scan of recent docs
        try {
            Query q = new Query().limit(LINEAR_SCAN_LIMIT).with(Sort.by(Sort.Direction.DESC, F_PUBLISHED_AT));
            List<Document> recent = mongoTemplate.find(q, Document.class, newsCollection);
            double best = -2.0d;
            for (Document d : recent) {
                List<?> arr = (List<?>) d.get(F_EMBEDDING);
                if (arr == null || arr.size() != EMBEDDING_DIM) continue;
                double[] other = new double[EMBEDDING_DIM];
                for (int i = 0; i < EMBEDDING_DIM; i++) {
                    Object v = arr.get(i);
                    other[i] = (v instanceof Number) ? ((Number) v).doubleValue() : 0.0d;
                }
                double s = cosine(embedding, other);
                if (s > best) best = s;
            }
            return best;
        } catch (Throwable t) {
            return -2.0d;
        }
    }

    private void saveArticle(Headline h, double[] embedding, String topic, String contentHash) {
        try {
            Document doc = new Document();
            doc.put(F_TITLE, nvl(h.getTitle()));
            doc.put(F_DESC, nvl(h.getDescription()));
            doc.put(F_SOURCE, nvl(h.getSource()));
            doc.put(F_URL, nvl(h.getLink()));
            doc.put(F_PUBLISHED_AT, h.getPublishedAt() != null ? h.getPublishedAt().toEpochMilli() : System.currentTimeMillis());
            if (embedding != null) doc.put(F_EMBEDDING, embedding);
            if (topic != null) doc.put(F_TOPIC, topic);
            if (contentHash != null) doc.put(F_HASH, contentHash);
            mongoTemplate.insert(doc, newsCollection);
        } catch (Throwable t) {
            // best-effort
        }
    }
}
