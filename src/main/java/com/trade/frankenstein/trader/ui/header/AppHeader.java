package com.trade.frankenstein.trader.ui.header;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.service.HeaderStatusService;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.annotation.UIScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Premium Enhanced App Header with modern design, optimized performance, and improved maintainability.
 *
 * Features:
 * - Premium branding with SVG logo and modern typography
 * - Responsive layout with mobile-first design
 * - Animated status indicators with smooth transitions
 * - Debounced updates to prevent UI thrashing
 * - Dependency injection for better testability
 * - Background processing for improved performance
 * - Accessibility-compliant components
 * - Theming support with CSS custom properties
 */
@CssImport("styles/theme.css")
@CssImport("styles/premium-header.css")
@CssImport("styles/variables.css")
@JsModule("styles/js/header-animations.js")
@Component
@UIScope
public class AppHeader extends Composite<Div> {

    private static final Logger log = Logger.getLogger(AppHeader.class.getName());
    private static final long DEBOUNCE_DELAY_MS = 200L;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Dependency injection
    private final ObjectMapper objectMapper;
    private final HeaderStatusService statusService;
    private final ScheduledExecutorService backgroundExecutor;

    // UI Components
    private final HorizontalLayout root = new HorizontalLayout();
    private final HorizontalLayout leftSection = new HorizontalLayout();
    private final HorizontalLayout rightSection = new HorizontalLayout();

    // Branding components
    private final Div logoContainer = new Div();
    private final Span brandPrimary = new Span("Trade");
    private final Span brandAccent = new Span("Frankenstein");
    private final Div liveIndicator = new Div();

    // Status chips with enhanced functionality
    private final EnhancedStatusChip engineChip = new EnhancedStatusChip("Engine", LiveStatus.UNKNOWN);
    private final EnhancedStatusChip sseChip = new EnhancedStatusChip("SSE", LiveStatus.DOWN);
    private final EnhancedStatusChip modelIdChip = new EnhancedStatusChip("Model", LiveStatus.UNKNOWN);
    private final EnhancedStatusChip performanceChip = new EnhancedStatusChip("P95", LiveStatus.UNKNOWN);
    private final EnhancedStatusChip versionChip = new EnhancedStatusChip("Version", LiveStatus.UNKNOWN);
    private final EnhancedStatusChip clockChip = new EnhancedStatusChip("Time", LiveStatus.UNKNOWN);

    // Mobile-responsive dropdown for collapsed status
    private final Div mobileStatusDropdown = new Div();
    private final Span mobileStatusButton = new Span();

    // State management
    private Registration statusSubscription;
    private final Map<String, Object> lastKnownState = new HashMap<>();
    private volatile boolean isAttached = false;

    /**
     * Constructor with dependency injection
     */
    @Autowired
    public AppHeader(HeaderStatusService statusService,
                     ObjectMapper objectMapper,
                     ScheduledExecutorService backgroundExecutor) {
        this.statusService = statusService;
        this.objectMapper = objectMapper;
        this.backgroundExecutor = backgroundExecutor;

        initializeComponents();
        setupLayout();
        applyInitialStyles();
    }

    /**
     * Initialize all UI components with premium styling
     */
    private void initializeComponents() {
        // Configure root container
        getContent().setSizeFull();
        getContent().addClassName("tf-premium-header-container");

        // Setup main layout
        root.addClassName("tf-premium-header");
        root.setWidthFull();
        root.setHeight("64px");
        root.setAlignItems(FlexComponent.Alignment.CENTER);
        root.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        root.setPadding(false);
        root.setSpacing(false);
        root.setMargin(false);

        // Initialize branding with logo
        initializeBranding();

        // Initialize status chips
        initializeStatusChips();

        // Initialize mobile responsive elements
        initializeMobileElements();
    }

    /**
     * Initialize premium branding components
     */
    private void initializeBranding() {
        // Logo container with SVG support
        logoContainer.addClassName("tf-logo-container");
        logoContainer.getElement().setProperty("innerHTML",
                "<svg class='tf-logo-icon' viewBox='0 0 24 24' fill='none' xmlns='http://www.w3.org/2000/svg'>" +
                        "<path d='M12 2L13.09 8.26L20 9L13.09 9.74L12 16L10.91 9.74L4 9L10.91 8.26L12 2Z' " +
                        "fill='var(--tf-brand-primary)'/></svg>");

        // Brand text with premium typography
        brandPrimary.addClassName("tf-brand-primary");
        brandAccent.addClassName("tf-brand-accent");

        // Live indicator with pulse animation
        liveIndicator.addClassName("tf-live-indicator");
        liveIndicator.getElement().setAttribute("aria-label", "System Status Indicator");

        // Accessibility attributes
        logoContainer.getElement().setAttribute("role", "img");
        logoContainer.getElement().setAttribute("aria-label", "Trade Frankenstein Logo");
    }

    /**
     * Initialize enhanced status chips with accessibility
     */
    private void initializeStatusChips() {
        List<EnhancedStatusChip> allChips = Arrays.asList(
                engineChip, sseChip, modelIdChip, performanceChip, versionChip, clockChip
        );

        allChips.forEach(chip -> {
            chip.addClassName("tf-status-chip");
            chip.getElement().setAttribute("role", "status");
            chip.getElement().setAttribute("aria-live", "polite");
            chip.getElement().setAttribute("tabindex", "0");
        });
    }


    /**
     * Initialize mobile-responsive elements
     */
    private void initializeMobileElements() {
        mobileStatusButton.addClassName("tf-mobile-status-button");
        mobileStatusButton.setText("Status");
        mobileStatusButton.getElement().setAttribute("aria-expanded", "false");
        mobileStatusButton.getElement().setAttribute("aria-controls", "mobile-status-dropdown");

        mobileStatusDropdown.addClassName("tf-mobile-status-dropdown");
        mobileStatusDropdown.setId("mobile-status-dropdown");
        mobileStatusDropdown.getElement().setAttribute("aria-hidden", "true");

        // Click handler for mobile dropdown
        mobileStatusButton.addClickListener(e -> toggleMobileDropdown());
    }

    /**
     * Setup the layout structure with responsive design
     */
    private void setupLayout() {
        // Left section - branding
        leftSection.addClassName("tf-header-section--left");
        leftSection.setPadding(false);
        leftSection.setMargin(false);
        leftSection.setSpacing(true);
        leftSection.setAlignItems(FlexComponent.Alignment.CENTER);

        HorizontalLayout brandContainer = new HorizontalLayout(logoContainer, brandPrimary, brandAccent, liveIndicator);
        brandContainer.setPadding(false);
        brandContainer.setMargin(false);
        brandContainer.setSpacing(true);
        brandContainer.setAlignItems(FlexComponent.Alignment.CENTER);

        leftSection.add(brandContainer);

        // Right section - status chips
        rightSection.addClassName("tf-header-section--right");
        rightSection.setPadding(false);
        rightSection.setMargin(false);
        rightSection.setSpacing(true);
        rightSection.setAlignItems(FlexComponent.Alignment.CENTER);
        rightSection.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        // Desktop status chips
        HorizontalLayout desktopChips = new HorizontalLayout(
                engineChip, sseChip, modelIdChip, performanceChip, versionChip, clockChip
        );
        desktopChips.addClassName("tf-desktop-chips");
        desktopChips.setPadding(false);
        desktopChips.setMargin(false);
        desktopChips.setSpacing(true);

        // Mobile status elements
        mobileStatusDropdown.add(
                new Div(engineChip.clone()),
                new Div(sseChip.clone()),
                new Div(modelIdChip.clone()),
                new Div(performanceChip.clone()),
                new Div(versionChip.clone()),
                new Div(clockChip.clone())
        );

        rightSection.add(desktopChips, mobileStatusButton);

        // Layout flex rules
        root.setFlexGrow(0, leftSection);
        root.setFlexGrow(1, rightSection);

        // Add to root
        root.add(leftSection, rightSection);
        getContent().add(root, mobileStatusDropdown);
    }

    /**
     * Apply initial styles and theme
     */
    private void applyInitialStyles() {
        // Set initial chip states
        engineChip.updateStatus(LiveStatus.UNKNOWN, "Initializing...");
        sseChip.updateStatus(LiveStatus.DOWN, "Connecting...");
        modelIdChip.updateStatus(LiveStatus.UNKNOWN, "Loading...");
        performanceChip.updateStatus(LiveStatus.UNKNOWN, "Measuring...");
        versionChip.updateStatus(LiveStatus.UNKNOWN, "Loading...");
        clockChip.updateStatus(LiveStatus.UNKNOWN, "--:--:--");

        // Apply premium theme
        getElement().setAttribute("data-theme", "premium");
    }

    // ===== Lifecycle Management =====

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        isAttached = true;

        UI ui = attachEvent.getUI();
        log.info("Enhanced AppHeader attached with premium features");

        // Start background services
        startServices(ui);

        // Initialize client-side features
        initializeClientSideFeatures(ui);

        // Subscribe to status updates
        subscribeToStatusUpdates();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        isAttached = false;

        // Cleanup resources
        if (statusSubscription != null) {
            statusSubscription.remove();
            statusSubscription = null;
        }

        // Stop services gracefully
        stopServices();

        log.info("Enhanced AppHeader detached and cleaned up");
    }

    /**
     * Start background services
     */
    private void startServices(UI ui) {
        // Start the header status service
        statusService.start();

        // Start client clock with IST timezone
        startClientClock(ui);

        // Initialize SSE connection
        initializeSSEConnection(ui);

        // Fetch initial snapshot
        fetchInitialSnapshot(ui);
    }

    /**
     * Stop background services
     */
    private void stopServices() {
        statusService.stop();

        // Stop client-side timers and connections
        getUI().ifPresent(ui -> ui.getPage().executeJs(
                "try { " +
                        "  if (window.__tfClockTimer) clearInterval(window.__tfClockTimer); " +
                        "  if (window.__tfSSEConnection) window.__tfSSEConnection.close(); " +
                        "} catch (e) { console.warn('Cleanup error:', e); }"
        ));
    }

    /**
     * Initialize client-side features with enhanced error handling
     */
    private void initializeClientSideFeatures(UI ui) {
        // Load header animations module
        ui.getPage().executeJs(
                "import('./js/header-animations.js').then(module => {" +
                        "  window.__tfHeaderAnimations = module;" +
                        "  module.initializeAnimations($0);" +
                        "}).catch(err => console.warn('Animation module failed to load:', err));",
                getElement()
        );
    }

    /**
     * Start high-precision IST client clock
     */
    private void startClientClock(UI ui) {
        ui.getPage().executeJs(
                "const startClock = () => {" +
                        "  const updateTime = () => {" +
                        "    try {" +
                        "      const now = new Date();" +
                        "      const formatter = new Intl.DateTimeFormat('en-IN', {" +
                        "        timeZone: 'Asia/Kolkata'," +
                        "        year: 'numeric'," +
                        "        month: '2-digit'," +
                        "        day: '2-digit'," +
                        "        hour: '2-digit'," +
                        "        minute: '2-digit'," +
                        "        second: '2-digit'," +
                        "        hour12: false" +
                        "      });" +
                        "      const timeString = formatter.format(now).replace(/\\u200E/g, '');" +
                        "      $0.$server.updateClock(timeString);" +
                        "    } catch (e) {" +
                        "      console.warn('Clock update failed:', e);" +
                        "    }" +
                        "  };" +
                        "  if (window.__tfClockTimer) clearInterval(window.__tfClockTimer);" +
                        "  window.__tfClockTimer = setInterval(updateTime, 1000);" +
                        "  updateTime();" +
                        "};" +
                        "startClock();",
                getElement()
        );
    }

    /**
     * Initialize SSE connection with fallback URLs
     */
    private void initializeSSEConnection(UI ui) {
        ui.getPage().executeJs(
                "const initSSE = () => {" +
                        "  const endpoints = [" +
                        "    '/api/stream?topics=heartbeat,engine.state,flags'," +
                        "    '/stream/sse?topics=heartbeat,engine.state,flags'," +
                        "    '/stream?topics=heartbeat,engine.state,flags'," +
                        "    '/sse?topics=heartbeat,engine.state,flags'" +
                        "  ];" +
                        "  " +
                        "  let currentConnection = null;" +
                        "  let connectionAttempt = 0;" +
                        "  " +
                        "  const connect = (endpointIndex = 0) => {" +
                        "    if (endpointIndex >= endpoints.length) {" +
                        "      $0.$server.onSSEEvent('connection.failed', '{}');" +
                        "      return;" +
                        "    }" +
                        "    " +
                        "    try {" +
                        "      if (currentConnection) currentConnection.close();" +
                        "      " +
                        "      currentConnection = new EventSource(endpoints[endpointIndex]);" +
                        "      let connectionOpened = false;" +
                        "      " +
                        "      currentConnection.onopen = () => {" +
                        "        connectionOpened = true;" +
                        "        connectionAttempt = 0;" +
                        "        window.__tfSSEConnection = currentConnection;" +
                        "        $0.$server.onSSEEvent('connection.opened', JSON.stringify({endpoint: endpoints[endpointIndex]}));" +
                        "      };" +
                        "      " +
                        "      currentConnection.onerror = () => {" +
                        "        if (!connectionOpened) {" +
                        "          setTimeout(() => connect(endpointIndex + 1), 1000 * Math.min(connectionAttempt++, 5));" +
                        "        } else {" +
                        "          $0.$server.onSSEEvent('connection.error', '{}');" +
                        "        }" +
                        "      };" +
                        "      " +
                        "      currentConnection.onmessage = (event) => {" +
                        "        try {" +
                        "          const data = JSON.parse(event.data || '{}');" +
                        "          $0.$server.onSSEEvent(data.topic || data.event || 'heartbeat', JSON.stringify(data));" +
                        "        } catch (e) {" +
                        "          console.warn('SSE message parsing failed:', e, event.data);" +
                        "        }" +
                        "      };" +
                        "      " +
                        "    } catch (e) {" +
                        "      setTimeout(() => connect(endpointIndex + 1), 1000 * Math.min(connectionAttempt++, 5));" +
                        "    }" +
                        "  };" +
                        "  " +
                        "  connect();" +
                        "};" +
                        "initSSE();",
                getElement()
        );
    }

    /**
     * Fetch initial system snapshot
     */
    private void fetchInitialSnapshot(UI ui) {
        ui.getPage().executeJs(
                "fetch('/api/header/snapshot', {" +
                        "  method: 'GET'," +
                        "  headers: { 'Accept': 'application/json' }," +
                        "  signal: AbortSignal.timeout(5000)" +
                        "})" +
                        ".then(response => {" +
                        "  if (!response.ok) throw new Error(`HTTP ${response.status}`);" +
                        "  return response.json();" +
                        "})" +
                        ".then(data => $0.$server.onSSEEvent('snapshot.loaded', JSON.stringify(data)))" +
                        ".catch(error => {" +
                        "  console.warn('Snapshot fetch failed:', error);" +
                        "  $0.$server.onSSEEvent('snapshot.failed', JSON.stringify({error: error.message}));" +
                        "});",
                getElement()
        );
    }

    /**
     * Subscribe to status updates from the service layer
     */
    private void subscribeToStatusUpdates() {
        Disposable disposable = statusService.subscribe(this::handleStatusUpdate);

        // Wrap Reactor Disposable in Vaadin Registration for compatibility
        statusSubscription = () -> {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        };
    }


    // ===== Event Handlers =====

    /**
     * Handle SSE events from client-side with improved error handling
     */
    @ClientCallable
    public void onSSEEvent(String eventType, String jsonData) {
        if (eventType == null || !isAttached) return;

        // Process in background to avoid blocking UI thread
        CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode data = objectMapper.readTree(
                        jsonData != null ? jsonData.getBytes(StandardCharsets.UTF_8) : "{}".getBytes()
                );
                return new ProcessedEvent(eventType, data);
            } catch (Exception e) {
                log.warning("Failed to parse SSE event: " + eventType + ", data: " + jsonData);
                return null;
            }
        }, backgroundExecutor).thenAcceptAsync(event -> {
            if (event != null && isAttached) {
                getUI().ifPresent(ui -> ui.access(() -> processSSEEvent(event)));
            }
        }, backgroundExecutor);
    }

    /**
     * Handle status updates from service layer
     */
    private void handleStatusUpdate(HeaderStatusService.StatusUpdate update) {
        if (!isAttached) return;

        getUI().ifPresent(ui -> ui.access(() -> {
            switch (update.getType()) {
                case ENGINE_STATUS:
                    updateEngineStatus(update.getData());
                    break;
                case SSE_HEALTH:
                    updateSSEHealth(update.getData());
                    break;
                case PERFORMANCE_METRICS:
                    updatePerformanceMetrics(update.getData());
                    break;
                case SYSTEM_INFO:
                    updateSystemInfo(update.getData());
                    break;
            }
        }));
    }

    /**
     * Process SSE events with debouncing
     */
    private void processSSEEvent(ProcessedEvent event) {
        String eventType = event.type;
        JsonNode data = event.data;

        // Debounce rapid updates
        Object lastValue = lastKnownState.get(eventType);
        if (lastValue != null && lastValue.equals(data.toString())) {
            return; // No change, skip update
        }
        lastKnownState.put(eventType, data.toString());

        switch (eventType) {
            case "connection.opened":
                sseChip.updateStatus(LiveStatus.UP, "Connected");
                updateLiveIndicator(true);
                break;
            case "connection.error":
            case "connection.failed":
                sseChip.updateStatus(LiveStatus.DOWN, "Disconnected");
                updateLiveIndicator(false);
                break;
            case "heartbeat":
                processHeartbeatEvent(data);
                break;
            case "engine.state":
                processEngineStateEvent(data);
                break;
            case "snapshot.loaded":
                processSnapshotEvent(data);
                break;
        }
    }

    /**
     * Process heartbeat events with comprehensive data extraction
     */
    private void processHeartbeatEvent(JsonNode heartbeat) {
        if (heartbeat == null || heartbeat.isMissingNode()) return;

        // Engine status
        JsonNode engineStatus = heartbeat.at("/engine/status");
        if (!engineStatus.isMissingNode()) {
            updateEngineStatusFromHeartbeat(engineStatus.asText());
        }

        // Model information
        JsonNode modelId = heartbeat.at("/modelId");
        if (!modelId.isMissingNode()) {
            modelIdChip.updateStatus(LiveStatus.UP, "Id: " + modelId.asText());
        }

        // Performance metrics
        JsonNode p95 = heartbeat.at("/infer/p95");
        if (p95.isNumber()) {
            updatePerformanceChip(p95.asLong());
        }

        // Version information
        JsonNode version = heartbeat.at("/build/version");
        JsonNode buildTime = heartbeat.at("/build/time");
        if (!version.isMissingNode() || !buildTime.isMissingNode()) {
            updateVersionInfo(
                    version.isMissingNode() ? "unknown" : version.asText(),
                    buildTime.isMissingNode() ? "unknown" : buildTime.asText()
            );
        }
    }

    /**
     * Process engine state events with enhanced status detection
     */
    private void processEngineStateEvent(JsonNode engineState) {
        if (engineState == null || engineState.isMissingNode()) return;

        boolean isRunning = engineState.path("running").asBoolean(false);
        String lastError = engineState.path("lastError").asText("");
        String lastTick = engineState.path("lastTick").asText("");
        String asOf = engineState.path("asOf").asText("");
        long ticks = engineState.path("ticks").asLong(-1);

        // Calculate staleness
        long stalenessSeconds = calculateStaleness(lastTick, asOf);

        // Determine status
        LiveStatus status;
        String statusText;
        String tooltip;

        if (!lastError.isEmpty()) {
            status = LiveStatus.DOWN;
            statusText = "Engine: ERROR";
            tooltip = "Error: " + lastError;
        } else if (isRunning) {
            if (stalenessSeconds > 30) {
                status = LiveStatus.DEGRADED;
                statusText = "Engine: STALE";
                tooltip = String.format("Running but data is %d seconds old", stalenessSeconds);
            } else {
                status = LiveStatus.UP;
                statusText = "Engine: RUNNING";
                tooltip = String.format("Running • %d ticks • Last: %s", ticks,
                        lastTick.isEmpty() ? "unknown" : lastTick);
            }
        } else {
            status = LiveStatus.DOWN;
            statusText = "Engine: STOPPED";
            tooltip = "Engine is not running";
        }

        engineChip.updateStatus(status, statusText, tooltip);
    }

    /**
     * Process initial snapshot data
     */
    private void processSnapshotEvent(JsonNode snapshot) {
        // Process as heartbeat for consistent handling
        processHeartbeatEvent(snapshot);

        log.info("Initial system snapshot processed");
    }

    // ===== Update Methods =====

    /**
     * Update engine status from various sources
     */
    private void updateEngineStatusFromHeartbeat(String status) {
        LiveStatus liveStatus = LiveStatus.fromString(status);
        String displayText = "Engine: " + (status != null ? status.toUpperCase() : "UNKNOWN");
        engineChip.updateStatus(liveStatus, displayText);
    }

    /**
     * Update engine status with detailed information
     */
    private void updateEngineStatus(Object statusData) {
        // Implementation depends on your status data structure
        // This is a placeholder for the actual implementation
    }

    /**
     * Update SSE health status
     */
    private void updateSSEHealth(Object healthData) {
        // Implementation depends on your health data structure
    }

    /**
     * Update performance metrics display
     */
    private void updatePerformanceMetrics(Object metricsData) {
        // Implementation depends on your metrics data structure
    }

    /**
     * Update system information
     */
    private void updateSystemInfo(Object systemData) {
        // Implementation depends on your system data structure
    }

    /**
     * Update performance chip based on P95 latency
     */
    private void updatePerformanceChip(long p95Millis) {
        LiveStatus status;
        String statusText = String.format("P95: %dms", p95Millis);
        String tooltip;

        if (p95Millis <= 0) {
            status = LiveStatus.UNKNOWN;
            statusText = "P95: --";
            tooltip = "No performance data available";
        } else if (p95Millis > 2000) {
            status = LiveStatus.DOWN;
            tooltip = "Performance is critically slow";
        } else if (p95Millis > 1000) {
            status = LiveStatus.DEGRADED;
            tooltip = "Performance is slower than expected";
        } else {
            status = LiveStatus.UP;
            tooltip = "Performance is optimal";
        }

        performanceChip.updateStatus(status, statusText, tooltip);
    }

    /**
     * Update version information
     */
    private void updateVersionInfo(String version, String buildTime) {
        String displayText = String.format("v%s", version);
        String tooltip = String.format("Version: %s • Built: %s", version, buildTime);
        versionChip.updateStatus(LiveStatus.UP, displayText, tooltip);
    }

    /**
     * Update live indicator animation
     */
    private void updateLiveIndicator(boolean isLive) {
        if (isLive) {
            liveIndicator.addClassName("tf-live-indicator--active");
            liveIndicator.removeClassName("tf-live-indicator--inactive");
        } else {
            liveIndicator.addClassName("tf-live-indicator--inactive");
            liveIndicator.removeClassName("tf-live-indicator--active");
        }
    }

    /**
     * Update clock display
     */
    @ClientCallable
    private void updateClock(String timeString) {
        if (timeString != null && isAttached) {
            clockChip.updateStatus(LiveStatus.UP, timeString, "IST (Indian Standard Time)");
        }
    }

    /**
     * Toggle mobile dropdown visibility
     */
    private void toggleMobileDropdown() {
        boolean isExpanded = "true".equals(
                mobileStatusButton.getElement().getAttribute("aria-expanded")
        );

        mobileStatusButton.getElement().setAttribute("aria-expanded", String.valueOf(!isExpanded));
        mobileStatusDropdown.getElement().setAttribute("aria-hidden", String.valueOf(isExpanded));

        if (!isExpanded) {
            mobileStatusDropdown.addClassName("tf-mobile-status-dropdown--visible");
        } else {
            mobileStatusDropdown.removeClassName("tf-mobile-status-dropdown--visible");
        }
    }

    // ===== Utility Methods =====

    /**
     * Calculate data staleness in seconds
     */
    private long calculateStaleness(String lastTick, String asOf) {
        try {
            String timestamp = !asOf.isEmpty() ? asOf : lastTick;
            if (timestamp.isEmpty()) return -1;

            Instant reference = Instant.parse(timestamp);
            return Duration.between(reference, Instant.now()).getSeconds();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    /**
     * Safe null-value handling
     */
    private static String nvl(String value, String defaultValue) {
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }

    // ===== Inner Classes =====

    /**
     * Enhanced status chip with smooth animations and accessibility
     */
    private static class EnhancedStatusChip extends Span {
        private LiveStatus currentStatus = LiveStatus.UNKNOWN;
        private String currentText = "";
        private String currentTooltip = "";

        public EnhancedStatusChip(String label, LiveStatus initialStatus) {
            super();
            addClassName("tf-enhanced-status-chip");
            updateStatus(initialStatus, label);
        }

        public void updateStatus(LiveStatus status, String text) {
            updateStatus(status, text, null);
        }

        public void updateStatus(LiveStatus status, String text, String tooltip) {
            // Only update if there's a change to avoid unnecessary DOM manipulation
            if (status == currentStatus && text.equals(currentText) &&
                    Objects.equals(tooltip, currentTooltip)) {
                return;
            }

            currentStatus = status;
            currentText = text;
            currentTooltip = tooltip;

            setText(text);

            // Remove old status classes
            getClassNames().removeAll(Arrays.asList("status-up", "status-down", "status-degraded", "status-unknown"));

            // Add new status class
            addClassName("status-" + status.getCssClass());

            // Update tooltip
            if (tooltip != null && !tooltip.isEmpty()) {
                getElement().setProperty("title", tooltip);
            }

            // Update aria-label for accessibility
            getElement().setAttribute("aria-label", text + (tooltip != null ? ". " + tooltip : ""));
        }

        public EnhancedStatusChip clone() {
            EnhancedStatusChip clone = new EnhancedStatusChip("", currentStatus);
            clone.updateStatus(currentStatus, currentText, currentTooltip);
            return clone;
        }
    }

    /**
     * Processed SSE event container
     */
    private static class ProcessedEvent {
        final String type;
        final JsonNode data;

        ProcessedEvent(String type, JsonNode data) {
            this.type = type;
            this.data = data;
        }
    }

    /**
     * Live status enumeration with enhanced capabilities
     */
    public enum LiveStatus {
        UP("up", "System is operating normally"),
        DOWN("down", "System is experiencing issues"),
        DEGRADED("degraded", "System is operating with reduced performance"),
        UNKNOWN("unknown", "System status is unknown");

        private final String cssClass;
        private final String description;

        LiveStatus(String cssClass, String description) {
            this.cssClass = cssClass;
            this.description = description;
        }

        public String getCssClass() {
            return cssClass;
        }

        public String getDescription() {
            return description;
        }

        public static LiveStatus fromString(String status) {
            if (status == null) return UNKNOWN;

            String normalized = status.trim().toLowerCase();
            switch (normalized) {
                case "running":
                case "active":
                case "online":
                case "healthy":
                case "ok":
                    return UP;
                case "stopped":
                case "inactive":
                case "offline":
                case "error":
                case "failed":
                case "down":
                    return DOWN;
                case "degraded":
                case "warning":
                case "slow":
                case "partial":
                    return DEGRADED;
                default:
                    return UNKNOWN;
            }
        }
    }
}
