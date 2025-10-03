// MarketSentimentCard.java  (updated)
package com.trade.frankenstein.trader.ui.sections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.ui.bridge.ApiClient;
import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

import java.util.Locale;

public class MarketSentimentCard extends CardSection {

    private static final ObjectMapper M = com.trade.frankenstein.trader.ui.bridge.ApiClient.json();

    // Top row parts
    private final Div emojiContainer = new Div();
    private final Span emoji = new Span("😊");
    private final Span sentWord = new Span("Bullish");
    private final Span sentScore = new Span("76");

    // Bars
    private final ProgressBar confBar = new ProgressBar();
    private final ProgressBar accBar = new ProgressBar();
    private final Span confValue = new Span("72%");
    private final Span accValue = new Span("68%");

    public MarketSentimentCard() {
        super("Market Sentiment");
        getStyle()
                .set("grid-column", "var(--sentiment-span, span 4)")
                .set("box-shadow", "var(--lumo-box-shadow-m)")
                .set("border-radius", "16px")
                .set("background", "var(--lumo-base-color)")
                .set("overflow", "hidden");
        getElement().getThemeList().add("card");

        // Card header styling - using the actual title component from the parent class
        title.getStyle()
                .set("font-size", "18px")
                .set("font-weight", "600")
                .set("color", "var(--lumo-primary-text-color)")
                .set("margin", "16px 20px")
                .set("padding", "0")
                .set("background", "linear-gradient(to right, rgba(0,0,0,0.02), rgba(0,0,0,0.05))")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("width", "100%")
                .set("box-sizing", "border-box");

        // Content container styling
        content.getStyle()
                .set("padding", "20px")
                .set("background", "linear-gradient(145deg, rgba(255,255,255,0.7), rgba(240,240,240,0.3))")
                .set("backdrop-filter", "blur(10px)");
        content.setSpacing(false);
        content.setPadding(false);

        // ===== Top row =====
        // Styled emoji container with gradient background
        emojiContainer.add(emoji);
        emojiContainer.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("width", "48px")
                .set("height", "48px")
                .set("border-radius", "12px")
                .set("background", "linear-gradient(135deg, rgba(255,255,255,0.8), rgba(240,240,240,0.3))")
                .set("box-shadow", "0 4px 12px rgba(0,0,0,0.08)")
                .set("border", "1px solid rgba(255,255,255,0.6)");

        emoji.getStyle()
                .set("font-size", "28px")
                .set("line-height", "1");

        // Sentiment block styling
        Span sentLabel = new Span("Current Sentiment");
        sentLabel.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "13px")
                .set("font-weight", "500")
                .set("letter-spacing", "0.3px");

        sentWord.getStyle()
                .set("font-size", "22px")
                .set("font-weight", "700")
                .set("color", "var(--lumo-success-color)")
                .set("display", "block")
                .set("margin-top", "4px")
                .set("letter-spacing", "0.2px");

        FlexLayout wordBlock = new FlexLayout();
        wordBlock.setFlexDirection(FlexLayout.FlexDirection.COLUMN);
        wordBlock.add(sentLabel, sentWord);
        wordBlock.getStyle()
                .set("line-height", "1.2")
                .set("margin-left", "12px");

        // Score styling
        Div scoreContainer = new Div(sentScore);
        scoreContainer.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("min-width", "60px")
                .set("height", "44px")
                .set("border-radius", "10px")
                .set("background", "var(--lumo-base-color)")
                .set("box-shadow", "inset 0 2px 5px rgba(0,0,0,0.06)")
                .set("border", "1px solid var(--lumo-contrast-10pct)");

        sentScore.getStyle()
                .set("font-size", "24px")
                .set("font-weight", "800")
                .set("color", "var(--brand-600, #16a34a)")
                .set("font-feature-settings", "'tnum'")
                .set("letter-spacing", "-0.5px");

        // Top row layout
        HorizontalLayout top = new HorizontalLayout(emojiContainer, wordBlock, scoreContainer);
        top.setWidthFull();
        top.setAlignItems(FlexComponent.Alignment.CENTER);
        top.expand(wordBlock);
        top.getStyle()
                .set("gap", "12px")
                .set("padding-bottom", "20px")
                .set("min-height", "60px");

        // Divider
        Div divider = new Div();
        divider.getStyle()
                .set("width", "100%")
                .set("height", "1px")
                .set("background", "linear-gradient(to right, transparent, var(--lumo-contrast-10pct), transparent)")
                .set("margin", "4px 0 16px");

        // Add components to content
        content.add(top);
        content.add(divider);
        content.add(labeledBar("Confidence", confBar, confValue));
        content.add(labeledBar("Prediction Accuracy", accBar, accValue));

        // Initial demo values
        update("Bullish", 76, 72, 68);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        // Initial fetch: try /api/sentiment/now, fallback to /api/decision/quality
        try {
            JsonNode j = ApiClient.get("/api/sentiment/now", JsonNode.class);
            if (j != null) {
                String word = j.path("word").asText("Neutral");
                int score = j.path("score").asInt(50);
                int conf = j.path("confidence").asInt(score);

                update(word, score, conf, 0);
            }
        } catch (Throwable ignored) {
            try {
                JsonNode dq = ApiClient.get("/api/decision/quality", JsonNode.class);
                if (dq != null) {
                    int score = dq.path("score").asInt(50);
                    int conf = dq.path("confidence").asInt(score);
                    int acc = dq.path("Accuracy").asInt(50);
                    String trend = dq.path("trend").asText("");
                    String word = trendWordOrScore(trend, score);
                    update(word, score, conf, acc);
                }
            } catch (Throwable ignored2) {
            }
        }

        // SSE: live updates
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                  const cmp=$0;
                  window.addEventListener('sentiment.update', e => {
                    cmp.$server._onSentiment(e.detail);
                  });
                  window.addEventListener('decision.quality', e => {
                    cmp.$server._onDecisionQuality(e.detail);
                  });
                """, getElement()));
    }

    // ==== Public UI updater ====
    public void update(String word, int score, int confidence, int accuracy) {
        boolean bullish = word != null &&
                word.toLowerCase(Locale.ROOT).contains("bull");

        // Determine sentiment colors
        String mainColor = bullish ? "var(--lumo-success-color)" : "var(--lumo-error-color)";
        String emojiValue = bullish ? "😊" : "😔";
        String gradientStart = bullish ? "rgba(34, 197, 94, 0.1)" : "rgba(239, 68, 68, 0.1)";
        String gradientEnd = bullish ? "rgba(34, 197, 94, 0.01)" : "rgba(239, 68, 68, 0.01)";

        // Update emoji and container styling
        emoji.setText(emojiValue);
        emojiContainer.getStyle().set("background",
                String.format("linear-gradient(135deg, %s, %s)", gradientStart, gradientEnd));

        // Update text elements
        sentWord.setText(word);
        sentWord.getStyle().set("color", mainColor);

        sentScore.setText(String.valueOf(score));
        sentScore.getStyle().set("color", mainColor);

        // Update progress bars
        confBar.setValue(confidence / 100.0);
        accBar.setValue(accuracy / 100.0);

        // Set color based on value ranges
        String confColor = getColorForValue(confidence);
        String accColor = getColorForValue(accuracy);

        confBar.getStyle().set("--lumo-primary-color", confColor);
        accBar.getStyle().set("--lumo-primary-color", accColor);

        // Update value labels
        confValue.setText(confidence + "%");
        accValue.setText(accuracy + "%");
    }

    // ---- Helpers ----
    private String getColorForValue(int value) {
        if (value >= 70) return "var(--lumo-success-color)";
        if (value >= 50) return "var(--lumo-primary-color)";
        if (value >= 30) return "var(--lumo-contrast-color)";
        return "var(--lumo-error-color)";
    }

    private Div labeledBar(String label, ProgressBar bar, Span valueDisplay) {
        // Create label with icon placeholder for future enhancement
        HorizontalLayout labelRow = new HorizontalLayout();
        labelRow.setWidthFull();
        labelRow.setSpacing(false);
        labelRow.setPadding(false);

        Span lab = new Span(label);
        lab.getStyle()
                .set("font-size", "13px")
                .set("font-weight", "500")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("letter-spacing", "0.1px");

        valueDisplay.getStyle()
                .set("font-size", "14px")
                .set("font-weight", "600")
                .set("color", "var(--lumo-primary-text-color)")
                .set("margin-left", "auto")
                .set("font-feature-settings", "'tnum'");

        labelRow.add(lab, valueDisplay);

        // Style progress bar for premium look
        bar.setMin(0);
        bar.setMax(1);
        bar.setWidthFull();
        bar.getStyle()
                .set("height", "10px")
                .set("border-radius", "999px")
                .set("overflow", "hidden")
                .set("--lumo-primary-color", "#22c55e")
                .set("box-shadow", "inset 0 1px 3px rgba(0,0,0,0.1)")
                .set("margin-top", "6px");

        Div row = new Div(labelRow, bar);
        row.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("width", "100%")
                .set("gap", "6px")
                .set("margin", "8px 0")
                .set("padding", "4px 2px");

        return row;
    }

    private static String trendWordOrScore(String trend, int score) {
        String t = trend == null ? "" : trend.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("up") || t.contains("bull")) return "Bullish";
        if (t.startsWith("down") || t.contains("bear")) return "Bearish";
        if (score >= 55) return "Bullish";
        if (score <= 45) return "Bearish";
        return "Neutral";
    }

    // ---- SSE server hooks ----
    @ClientCallable
    private void _onSentiment(String json) {
        try {
            JsonNode j = M.readTree(json);
            String word = j.path("word").asText("Neutral");
            int score = j.path("score").asInt(50);
            int conf = j.path("confidence").asInt(score);
            int acc = j.path("accuracy").asInt(50);
            update(word, score, conf, acc);
        } catch (Throwable ignored) {
        }
    }

    @ClientCallable
    private void _onDecisionQuality(String json) {
        try {
            JsonNode j = M.readTree(json);
            int score = j.path("score").asInt(50);
            int conf = j.path("confidence").asInt(score);
            int acc = j.path("accuracy").asInt(50);
            String trend = j.path("trend").asText("");
            String word = trendWordOrScore(trend, score);
            update(word, score, conf, acc);
        } catch (Throwable ignored) {
        }
    }
}
