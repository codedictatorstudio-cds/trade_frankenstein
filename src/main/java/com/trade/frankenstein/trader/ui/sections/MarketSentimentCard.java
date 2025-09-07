package com.trade.frankenstein.trader.ui.sections;

import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

import java.util.Locale;

/**
 * UI-v2 aligned Market Sentiment card.
 * - Compact padding to match other cards
 * - Top row: Emoji | (label+word) ... Score (flush-right)
 * - Full-width bars with consistent 8px thickness
 * - Single update(...) entrypoint
 */
public class MarketSentimentCard extends CardSection {

    // Top row parts
    private final Span emoji = new Span("😊");
    private final Span sentWord = new Span("Bullish");
    private final Span sentScore = new Span("76");

    // Bars
    private final ProgressBar confBar = new ProgressBar();
    private final ProgressBar accBar = new ProgressBar();

    public MarketSentimentCard() {
        super("Market Sentiment");

        // Match padding used in other cards
        getStyle().setPadding("10px 12px 12px");

        // Optional grid span hook (parent grid may override this CSS var)
        getStyle().set("grid-column", "var(--sentiment-span, span 4)");

        // ===== Top row =====
        emoji.getStyle()
                .set("font-size", "28px")
                .set("line-height", "1");

        Span sentLabel = new Span("Current Sentiment");
        sentLabel.getStyle()
                .set("color", "#6B7280")
                .set("font-size", "12px")
                .set("font-weight", "600");

        sentWord.getStyle()
                .set("font-size", "20px")
                .set("font-weight", "700")
                .set("color", "var(--lumo-success-color)") // flips in update()
                .set("display", "block")
                .set("margin-top", "2px");

        Div wordBlock = new Div(sentLabel, sentWord);
        wordBlock.getStyle().set("display", "flex")
                .set("flex-direction", "column");

        sentScore.getStyle()
                .set("font-weight", "800")
                .set("font-size", "26px")
                .set("color", "var(--brand-600, #16a34a)");

        HorizontalLayout top = new HorizontalLayout(emoji, wordBlock, sentScore);
        top.setWidthFull();
        top.setAlignItems(FlexComponent.Alignment.CENTER);
        top.expand(wordBlock);                 // pushes score to the far right
        top.getStyle().set("gap", "12px")
                .set("padding-bottom", "8px")
                .set("min-height", "44px"); // visually aligns with sibling headers
        add(top);

        // ===== Bars =====
        add(labeledBar("Confidence", confBar));
        add(labeledBar("Prediction Accuracy", accBar));

        // Initial demo values
        update("Bullish", 76, 76, 74);
    }

    /** Primary API to refresh the card. */
    public void update(String word, int score, int confidence, int accuracy) {
        boolean bullish = word != null &&
                word.toLowerCase(Locale.ROOT).contains("bull");

        String color = bullish ? "var(--lumo-success-color)"
                : "var(--lumo-error-color)";

        sentWord.setText(word);
        sentWord.getStyle().set("color", color);

        sentScore.setText(String.valueOf(score));
        sentScore.getStyle().set("color", color);

        confBar.setValue(Math.max(0, Math.min(100, confidence)));
        accBar.setValue(Math.max(0, Math.min(100, accuracy)));
    }

    // ---- Helpers ----
    private Div labeledBar(String label, ProgressBar bar) {
        Span lab = new Span(label);
        lab.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", "#6B7280");

        bar.setMin(0); bar.setMax(100);
        bar.setWidthFull();
        bar.getStyle()
                .set("height", "8px")
                .set("border-radius", "999px")
                .set("overflow", "hidden")
                // match brand green; parent theme can override this var
                .set("--lumo-primary-color", "#22c55e");

        Div row = new Div(lab, bar);
        row.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "1fr")
                .set("gap", "6px")
                .set("margin-top", "6px");

        return row;
    }
}
