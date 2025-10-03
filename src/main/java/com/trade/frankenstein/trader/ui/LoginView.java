package com.trade.frankenstein.trader.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@Route("")
@PageTitle("TradeFrankenstein Login")
@CssImport("./styles/login-view.css")
public class LoginView extends Div {

    public LoginView() {
        setClassName("login-container");
        setWidthFull();
        setHeightFull();
        getStyle().set("text-align", "center");

        // ----- Brand panel (left) -----
        Div brandPanel = new Div();
        brandPanel.setClassName("login-brand-panel");

        Div brandContent = new Div();
        brandContent.setClassName("brand-content");

        // Logo (IMAGE inside circular badge) — preserves .brand-logo styles from your CSS
        Div logoWrap = getLogoWrap();

        H1 brandTitle = new H1("TradeFrankenstein");
        brandTitle.setClassName("brand-title");

        Paragraph brandSubtitle = new Paragraph(
                "Your intelligent trading companion for maximizing returns in today's dynamic markets");
        brandSubtitle.setClassName("brand-subtitle");

        UnorderedList featureList = buildFeatureList();

        brandContent.add(logoWrap, brandTitle, brandSubtitle, featureList);
        brandPanel.add(brandContent);

        // ----- Form panel (right) -----
        Div formPanel = new Div();
        formPanel.setClassName("login-form-panel");

        Div formContainer = new Div();
        formContainer.setClassName("login-form-container");

        Div formHeader = new Div();
        formHeader.setClassName("form-header");
        formHeader.getStyle().set("text-align", "center").set("margin-bottom", "40px");

        H2 formTitle = new H2();
        formTitle.setClassName("form-title");

        Span gradientTitle = new Span("Welcome to TradeFrankenstein");
        gradientTitle.getStyle()
                .set("color", "#00c853") // solid green text
                .set("font-weight", "800")
                .set("letter-spacing", "-0.5px")
                .set("font-size", "2.6rem")
                .set("font-size", "clamp(2.2rem, 5vw, 2.6rem)")
                .set("font-family", "'Poppins', var(--dd-font-primary)");
        formTitle.add(gradientTitle);


        Paragraph formSubtitle = new Paragraph("Access your trading dashboard");
        formSubtitle.setClassName("form-subtitle");
        formSubtitle.getStyle().set("margin-top", "10px").set("font-size", "1.1rem");

        formHeader.add(formTitle, formSubtitle);

        // Upstox login button (with lock icon)
        Button loginButton = new Button("Login to Upstox");
        loginButton.setClassName("btn btn-primary");
        loginButton.getStyle().set("max-width", "250px").set("margin", "20px 0");

        Element lockIconSpan = ElementFactory.createSpan();
        lockIconSpan.setProperty("innerHTML",
                "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='currentColor' class='bi bi-lock-fill' viewBox='0 0 16 16' style='margin-right: 8px; vertical-align: -2px;'>"
                        + "<path fill-rule='evenodd' d='M8 0a2 2 0 0 1 2 2v1h4a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H2a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4V2a2 2 0 0 1 2-2zm0 1a1 1 0 0 0-1 1v1h2V2a1 1 0 0 0-1-1zM3 5v10h10V5H3z'/>"
                        + "</svg>");
        loginButton.getElement().insertChild(0, lockIconSpan);

        // Enable login button to redirect to Upstox OAuth login
        loginButton.addClickListener(e ->
            getUI().ifPresent(ui -> ui.getPage().setLocation("/oauth/upstox/login"))
        );

        // Show error message if authentication failed
        List<String> errorParams = getUI().map(ui -> ui.getInternals().getActiveViewLocation().getQueryParameters().getParameters().get("error")).orElse(null);
        if (errorParams != null && !errorParams.isEmpty()) {
            Paragraph errorMsg = new Paragraph("Authentication Unsuccessful");
            errorMsg.getStyle().set("color", "#d32f2f").set("font-weight", "bold").set("margin-top", "20px");
            formContainer.add(errorMsg);
        }

        formContainer.add(formHeader, loginButton);
        formPanel.add(formContainer);

        // ----- Root -----
        add(brandPanel, formPanel);
    }

    @NotNull
    private static Div getLogoWrap() {
        Div logoWrap = new Div();
        logoWrap.setClassName("brand-logo");

        Image logoImg = new Image("icons/icon.png", "TradeFrankenstein");
        logoImg.addClassName("brand-logo");
        // Optional sizing if you want to enforce dimensions from code (CSS can override)
        logoImg.setWidth("72px");
        logoImg.setHeight("72px");

        logoWrap.add(logoImg);
        return logoWrap;
    }

    private UnorderedList buildFeatureList() {
        UnorderedList list = new UnorderedList();
        list.setClassName("feature-list");
        String[] items = {
                "Real-time market analysis & predictions",
                "Advanced risk management tools",
                "Automated trading strategies",
                "Comprehensive portfolio tracking"
        };
        for (String text : items) {
            ListItem li = new ListItem();
            li.setClassName("feature-item");
            Span icon = new Span("✓");
            icon.setClassName("feature-icon");
            Span label = new Span(text);
            li.add(icon, label);
            list.add(li);
        }
        return list;
    }
}
