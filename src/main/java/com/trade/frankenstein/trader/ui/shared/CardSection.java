package com.trade.frankenstein.trader.ui.shared;

import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

public class CardSection extends VerticalLayout {
    protected final H3 title;
    protected final VerticalLayout content;

    public CardSection(String heading) {
        this.title = new H3(heading);
        this.title.getStyle().set("font-weight", "bold");
        this.content = new VerticalLayout();
        this.content.setPadding(true);
        this.content.setSpacing(true);
        this.content.setMargin(false);


        setPadding(false);
        setSpacing(false);
        setMargin(false);
        setWidthFull();
        getStyle()
                .set("background", "#fff")
                .set("border", "1px solid #ddd")
                .set("border-radius", "6px");

        title.getStyle().set("margin", "8px 12px");
        content.getStyle().set("padding", "12px");

        add(title, content);
    }

    protected VerticalLayout body() {
        return content;
    }
}
