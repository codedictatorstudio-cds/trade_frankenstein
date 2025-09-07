package com.trade.frankenstein.trader.ui.header;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.shared.Registration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AppHeader extends Div {

    // Brand tokens
    private static final String BRAND_GREEN = "#18A557";
    private static final String BRAND_GREEN_LIGHT = "#22c55e";
    private static final String BRAND_GREEN_LIGHTEST = "#60f0a5";
    private static final String BRAND_BORDER = "#CFF6E1";
    private static final String BORDER = "#E6E8F0";
    private static final String TEXT_COLOR = "#0B1221";
    private static final String BLUE_TEXT = "#60A5FA";
    private static final String RED_TEXT = "#F87171";
    private static final String YELLOW_TEXT = "#FBBF24";

    // HUD tokens
    private static final String HUD_FS = "12px";
    private static final String HUD_PAD = "7px 11px";

    // Container paddings
    private static final String OUTER_PAD = "10px 16px";

    // Color themes
    private static final String STATUS_OK = "ok";
    private static final String STATUS_WARNING = "warn";
    private static final String STATUS_ERROR = "err";
    private static final String STATUS_BASE = "base";

    // Component references
    private Span modeValue;                 // LIVE / SANDBOX
    private Registration modeReg;           // listener registration
    private Button profileButton;           // User profile button
    private Button notificationButton;      // Notifications
    private Span notificationBadge;         // Notification count
    private int notificationCount = 0;      // Current notification count

    // Optional "Last Tick" visibility
    private boolean showLastTick = true;
    private HorizontalLayout lastTickBadgeRef;
    private HorizontalLayout brokerBadgeRef;

    // Date/time formatter for timestamps
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public AppHeader() {
        setWidthFull();
        getStyle()
                .set("position", "sticky")
                .set("top", "0")
                .set("z-index", "10")
                .set("background", "#FFFFFF")
                .set("box-shadow", "0 1px 3px rgba(0,0,0,0.05)")
                .set("border-bottom", "1px solid " + BORDER);

        Div inner = new Div();
        inner.setWidthFull();
        inner.getStyle()
                .set("max-width", "1280px")
                .set("margin", "0 auto")
                .set("padding", OUTER_PAD)
                .set("color", TEXT_COLOR);

        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setPadding(false);
        row.setSpacing(false);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        // Left side - Brand (logo + wordmark)
        HorizontalLayout leftSection = createBrandSection();

        // Center section - Quick status indicators
        HorizontalLayout centerSection = createCenterSection();

        // Right side - HUD: Mode / Broker / Last Tick + user controls
        HorizontalLayout rightSection = createRightSection();

        // Layout
        row.add(leftSection, centerSection, rightSection);
        inner.add(row);
        add(inner);

        // Initialize with current time
        updateLastTickTime();
    }

    private HorizontalLayout createBrandSection() {
        HorizontalLayout brand = new HorizontalLayout();
        brand.setPadding(false);
        brand.setSpacing(false);
        brand.setAlignItems(FlexComponent.Alignment.CENTER);
        brand.getStyle().set("display", "flex").set("gap", "10px");

        // Logo with gradient effect
        Div logo = new Div(new Span("TF"));
        logo.getStyle()
                .set("width", "28px")
                .set("height", "28px")
                .set("border-radius", "8px")
                .set("background", "linear-gradient(135deg," + BRAND_GREEN_LIGHT + "," + BRAND_GREEN_LIGHTEST + ")")
                .set("display", "grid")
                .set("place-items", "center")
                .set("color", "#FFFFFF")
                .set("font-weight", "900")
                .set("font-size", "14px")
                .set("box-shadow", "0 2px 4px rgba(0,0,0,0.1)");

        // Brand name with better typography
        Span brandText = new Span("TradeFrankenstein");
        brandText.getStyle()
                .set("font-size", "16px")
                .set("font-weight", "800")
                .set("letter-spacing", "-0.3px");

        // Make logo clickable to go to home/dashboard
        logo.getElement().addEventListener("click", e -> UI.getCurrent().navigate(""));
        logo.getStyle().set("cursor", "pointer");

        brand.add(logo, brandText);
        return brand;
    }

    private HorizontalLayout createCenterSection() {
        HorizontalLayout centerSection = new HorizontalLayout();
        centerSection.setPadding(false);
        centerSection.setSpacing(false);
        centerSection.setAlignItems(FlexComponent.Alignment.CENTER);
        centerSection.getStyle()
                .set("display", "flex")
                .set("gap", "16px")
                .set("justify-content", "center");

        // Market status indicator
        HorizontalLayout marketStatus = badge("Market:", "Open", STATUS_OK);

        // Index/Symbol quick view with value
        HorizontalLayout indexBadge = valueBadge("S&P 500:", "4,927.25", "+0.28%", true);

        // Add to center section
        centerSection.add(marketStatus, indexBadge);

        // Hide on mobile, will be shown only on desktop
        centerSection.getStyle()
                .set("display", "none");

        return centerSection;
    }

    private HorizontalLayout createRightSection() {
        HorizontalLayout rightSection = new HorizontalLayout();
        rightSection.setPadding(false);
        rightSection.setSpacing(false);
        rightSection.setAlignItems(FlexComponent.Alignment.CENTER);
        rightSection.getStyle()
                .set("display", "flex")
                .set("gap", "12px");

        // HUD: Mode / Broker / Last Tick (optional)
        HorizontalLayout hud = new HorizontalLayout();
        hud.setPadding(false);
        hud.setSpacing(false);
        hud.setAlignItems(FlexComponent.Alignment.CENTER);
        hud.getStyle().set("display", "flex").set("gap", "10px");

        // Trading mode indicator (LIVE/SANDBOX)
        HorizontalLayout mode = modeBadge("LIVE");

        // Broker connection status
        brokerBadgeRef = badge("Broker:", "OK", STATUS_OK);

        // Last price tick timestamp
        lastTickBadgeRef = badge("Last Tick:", "--:--:--", STATUS_BASE);

        hud.add(mode, brokerBadgeRef);
        if (showLastTick) hud.add(lastTickBadgeRef);

        // User controls (notifications, profile)
        HorizontalLayout userControls = createUserControls();

        rightSection.add(hud, userControls);
        return rightSection;
    }

    private HorizontalLayout createUserControls() {
        HorizontalLayout controls = new HorizontalLayout();
        controls.setPadding(false);
        controls.setSpacing(false);
        controls.setAlignItems(FlexComponent.Alignment.CENTER);
        controls.getStyle().set("display", "flex").set("gap", "8px");

        // Notification button with badge
        Div notificationWrapper = new Div();
        notificationWrapper.getStyle().set("position", "relative");

        notificationButton = new Button(new Icon(VaadinIcon.BELL));
        notificationButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        notificationButton.getStyle()
                .set("min-width", "32px")
                .set("width", "32px")
                .set("height", "32px");

        notificationBadge = new Span("0");
        notificationBadge.getStyle()
                .set("position", "absolute")
                .set("top", "-5px")
                .set("right", "-5px")
                .set("background", RED_TEXT)
                .set("color", "white")
                .set("border-radius", "50%")
                .set("font-size", "10px")
                .set("width", "16px")
                .set("height", "16px")
                .set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("font-weight", "bold")
                .set("visibility", "hidden");

        notificationWrapper.add(notificationButton, notificationBadge);

        // User profile button
        profileButton = new Button(new Icon(VaadinIcon.USER));
        profileButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        profileButton.getStyle()
                .set("min-width", "32px")
                .set("width", "32px")
                .set("height", "32px");

        controls.add(notificationWrapper, profileButton);
        return controls;
    }

    // Expose a toggle in case you don't stream LTP
    public void setShowLastTick(boolean show) {
        this.showLastTick = show;
        if (lastTickBadgeRef != null) {
            lastTickBadgeRef.setVisible(show);
        }
    }

    // Optional: update last-tick time from elsewhere
    public void setLastTickText(String hhmmss) {
        if (lastTickBadgeRef != null && lastTickBadgeRef.getComponentCount() >= 2) {
            ((Span) lastTickBadgeRef.getComponentAt(1)).setText(hhmmss);
        }
    }

    // Update last tick with current time
    public void updateLastTickTime() {
        String currentTime = LocalDateTime.now().format(timeFormatter);
        setLastTickText(currentTime);
    }

    // Update broker status with different states
    public void setBrokerStatus(String status, String type) {
        if (brokerBadgeRef != null && brokerBadgeRef.getComponentCount() >= 2) {
            ((Span) brokerBadgeRef.getComponentAt(1)).setText(status);

            // Reset styles
            brokerBadgeRef.getStyle()
                    .set("background", "#FFFFFF")
                    .set("border", "1px solid " + BORDER);

            // Apply appropriate styling
            if (STATUS_OK.equals(type)) {
                brokerBadgeRef.getStyle().set("border", "1px solid " + BRAND_BORDER);
            } else if (STATUS_WARNING.equals(type)) {
                brokerBadgeRef.getStyle().set("border", "1px solid #FDE68A").set("background", "#FFFBEB");
            } else if (STATUS_ERROR.equals(type)) {
                brokerBadgeRef.getStyle().set("border", "1px solid #FECACA").set("background", "#FFF1F2");
            }
        }
    }

    // Add notification and update badge
    public void addNotification() {
        notificationCount++;
        updateNotificationBadge();
    }

    // Add multiple notifications
    public void addNotifications(int count) {
        notificationCount += count;
        updateNotificationBadge();
    }

    // Clear notifications
    public void clearNotifications() {
        notificationCount = 0;
        updateNotificationBadge();
    }

    // Update notification badge visibility and count
    private void updateNotificationBadge() {
        if (notificationBadge != null) {
            if (notificationCount > 0) {
                notificationBadge.setText(String.valueOf(Math.min(notificationCount, 99)));
                notificationBadge.getStyle().set("visibility", "visible");
            } else {
                notificationBadge.getStyle().set("visibility", "hidden");
            }
        }
    }

    // Wire Mode changes from a UI-wide event
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (modeReg != null) modeReg.remove();
        modeReg = ComponentUtil.addListener(
                attachEvent.getUI(),
                ControlsBar.ModeChangedEvent.class,
                ev -> setMode(ev.isSandbox() ? "SANDBOX" : "LIVE")
        );
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (modeReg != null) {
            modeReg.remove();
            modeReg = null;
        }
    }

    private void setMode(String modeText) {
        if (modeValue != null) {
            modeValue.setText(modeText);

            // Update styling based on mode
            if ("SANDBOX".equals(modeText)) {
                modeValue.getStyle().set("color", YELLOW_TEXT);
            } else {
                modeValue.getStyle().set("color", BLUE_TEXT);
            }
        }
    }

    // --- Badges (Mode has a stable handle to its VALUE span) ---
    private HorizontalLayout modeBadge(String value) {
        Span label = new Span("Mode:");
        label.getStyle()
                .set("font-weight", "800")
                .set("color", BRAND_GREEN)
                .set("font-size", HUD_FS)
                .set("line-height", "1")
                .set("white-space", "nowrap");

        modeValue = new Span(value);
        modeValue.getStyle()
                .set("font-weight", "800")
                .set("margin-left", "5px")
                .set("font-size", HUD_FS)
                .set("line-height", "1")
                .set("white-space", "nowrap")
                .set("color", BLUE_TEXT);

        HorizontalLayout pill = new HorizontalLayout(label, modeValue);
        pill.setPadding(false);
        pill.setSpacing(false);
        pill.setAlignItems(FlexComponent.Alignment.CENTER);
        pill.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("white-space", "nowrap")
                .set("min-width", "max-content")
                .set("background", "#FFFFFF")
                .set("border", "1px solid " + BORDER)
                .set("border-radius", "999px")
                .set("padding", HUD_PAD);

        return pill;
    }

    private HorizontalLayout badge(String label, String value, String type) {
        Span l = new Span(label);
        l.getStyle()
                .set("font-weight", "800")
                .set("color", BRAND_GREEN)
                .set("font-size", HUD_FS)
                .set("line-height", "1")
                .set("white-space", "nowrap");

        Span v = new Span(value);
        v.getStyle()
                .set("font-weight", "800")
                .set("margin-left", "5px")
                .set("font-size", HUD_FS)
                .set("line-height", "1")
                .set("white-space", "nowrap")
                .set("color", BLUE_TEXT);

        HorizontalLayout pill = new HorizontalLayout(l, v);
        pill.setPadding(false);
        pill.setSpacing(false);
        pill.setAlignItems(FlexComponent.Alignment.CENTER);
        pill.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("white-space", "nowrap")
                .set("min-width", "max-content")
                .set("background", "#FFFFFF")
                .set("border", "1px solid " + BORDER)
                .set("border-radius", "999px")
                .set("padding", HUD_PAD);

        if (STATUS_OK.equals(type)) {
            pill.getStyle().set("border", "1px solid " + BRAND_BORDER);
        } else if (STATUS_WARNING.equals(type)) {
            pill.getStyle().set("border", "1px solid #FDE68A").set("background", "#FFFBEB");
        } else if (STATUS_ERROR.equals(type)) {
            pill.getStyle().set("border", "1px solid #FECACA").set("background", "#FFF1F2");
        }

        return pill;
    }

    // Value badge with trend indicator (for showing prices, indices)
    private HorizontalLayout valueBadge(String label, String value, String change, boolean positive) {
        HorizontalLayout badge = badge(label, value, STATUS_BASE);

        // Add trend indicator
        Span changeSpan = new Span(change);
        changeSpan.getStyle()
                .set("font-weight", "700")
                .set("margin-left", "8px")
                .set("font-size", "11px")
                .set("line-height", "1")
                .set("white-space", "nowrap")
                .set("color", positive ? BRAND_GREEN : RED_TEXT);

        Icon trendIcon = new Icon(positive ? VaadinIcon.ARROW_UP : VaadinIcon.ARROW_DOWN);
        trendIcon.setSize("10px");
        trendIcon.getStyle().set("color", positive ? BRAND_GREEN : RED_TEXT);

        Div changeWrapper = new Div(trendIcon, changeSpan);
        changeWrapper.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("gap", "2px")
                .set("margin-left", "4px");

        badge.add(changeWrapper);

        return badge;
    }

    // Get notification button for attaching events
    public Button getNotificationButton() {
        return notificationButton;
    }

    // Get profile button for attaching events
    public Button getProfileButton() {
        return profileButton;
    }
}
