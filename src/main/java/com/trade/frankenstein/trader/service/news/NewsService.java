package com.trade.frankenstein.trader.service.news;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.entity.MarketSentimentSnapshotEntity;
import com.trade.frankenstein.trader.repo.MarketSentimentSnapshotRepository;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private final MarketSentimentSnapshotRepository sentimentRepo;
    private final StreamGateway stream;

    @Value("#{'${trade.news.urls:}'.trim().isEmpty() ? null : '${trade.news.urls:}'.split(',')}")
    private List<String> feedUrls;

    @Value("#{'${trade.news.keywords.bullish:surge,record high,upbeat,rally,gains,rise,beat estimates,positive}'.split(',')}")
    private List<String> bullish;

    @Value("#{'${trade.news.keywords.bearish:plunge,selloff,downbeat,fall,losses,decline,miss estimates,negative}'.split(',')}")
    private List<String> bearish;

    @Value("${trade.news.min-items-for-high-confidence:12}")
    private int minItemsForHighConfidence;

    @Value("${trade.news.max-items-per-feed:30}")
    private int maxItemsPerFeed;

    @Transactional
    public Result<MarketSentimentSnapshotEntity> ingestAndUpdateSentiment() {
        try {
            List<String> urls = (feedUrls == null) ? Collections.emptyList() : trim(feedUrls);
            if (urls.isEmpty()) return Result.fail("NEWS_DISABLED_OR_NO_FEEDS");

            int bull = 0, bear = 0, total = 0;
            for (String u : urls) {
                try {
                    List<NewsItem> items = fetchFeed(u, maxItemsPerFeed);
                    for (NewsItem it : items) {
                        bull += hits(it, bullish);
                        bear += hits(it, bearish);
                    }
                    total += items.size();
                } catch (Exception ex) {
                    log.warn("Feed fetch failed ({}): {}", u, ex.getMessage());
                }
            }

            double raw = 0.0;
            if (bull + bear > 0) {
                raw = (bull - bear) / (double) (bull + bear);
                raw = clamp(raw, -1.0, 1.0);
            }
            int score = (int) Math.round(50.0 + raw * 50.0);

            int conf;
            if (total >= minItemsForHighConfidence) {
                conf = 80 + Math.min(20, (total - minItemsForHighConfidence));
            } else {
                conf = 40 + Math.min(40, total * 3);
            }
            conf = Math.max(0, Math.min(100, conf));

            MarketSentimentSnapshotEntity snap = new MarketSentimentSnapshotEntity();
            snap.setAsOf(Instant.now());
            snap.setSentimentScore(score);
            snap.setConfidence(conf);

            MarketSentimentSnapshotEntity saved = sentimentRepo.save(snap);
            try {
                stream.send("sentiment.update", saved);
            } catch (Throwable t) {
                log.info("WebSocket stream send failed: {}", t.getMessage());
            }
            return Result.ok(saved);
        } catch (Exception e) {
            log.error("NEWS_INGEST_FAILED", e);
            return Result.fail("NEWS_INGEST_FAILED: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Result<MarketSentimentSnapshotEntity> getLatestSentiment() {
        MarketSentimentSnapshotEntity latest = sentimentRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);
        return (latest == null) ? Result.fail("NO_SENTIMENT_SNAPSHOT") : Result.ok(latest);
    }

    // --- helpers (typed) ---
    private static List<String> trim(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private static int hits(NewsItem item, List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        String text = (item.title + " " + item.description).toLowerCase();
        int c = 0;
        for (String k : keys) {
            String kw = k.trim().toLowerCase();
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

    private static List<NewsItem> fetchFeed(String url, int maxItems) throws Exception {
        String xml = httpGet(url, 10_000, 10_000);
        List<NewsItem> items = new ArrayList<>();
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
                items.add(new NewsItem(text(e, "title"), text(e, "description")));
            }
            return items;
        }
        org.w3c.dom.NodeList atom = doc.getElementsByTagName("entry");
        int lim = Math.min(atom.getLength(), maxItems);
        for (int i = 0; i < lim; i++) {
            org.w3c.dom.Element e = (org.w3c.dom.Element) atom.item(i);
            items.add(new NewsItem(text(e, "title"), text(e, "summary")));
        }
        return items;
    }

    private static String httpGet(String u, int ct, int rt) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(ct);
        c.setReadTimeout(rt);
        c.setRequestProperty("User-Agent", "TradeFrankensteinBot/1.0");
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String text(org.w3c.dom.Element p, String tag) {
        org.w3c.dom.NodeList nl = p.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        org.w3c.dom.Node n = nl.item(0);
        return n == null ? "" : n.getTextContent();
    }

    private record NewsItem(String title, String description) {
        private NewsItem(String title, String description) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
        }
    }
}
