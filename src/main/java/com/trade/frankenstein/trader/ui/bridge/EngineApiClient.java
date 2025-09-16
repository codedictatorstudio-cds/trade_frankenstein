package com.trade.frankenstein.trader.ui.bridge;

import com.trade.frankenstein.trader.enums.EngineStatus;

/**
 * Client for interacting with the Engine Controller APIs.
 * This class provides a typed interface over the generic ApiClient.
 */
public class EngineApiClient {

    private static final String ENGINE_API_BASE = "/api/engine";

    /**
     * Get the current engine state
     *
     * @return The current engine state as a string
     */
    public static String getEngineState() {
        return ApiClient.get(ENGINE_API_BASE + "/state", String.class);
    }

    /**
     * Start the trading engine
     *
     * @return Response message
     */
    public static String startEngine() {
        return ApiClient.post(ENGINE_API_BASE + "/start", null, String.class);
    }

    /**
     * Stop the trading engine
     *
     * @return Response message
     */
    public static String stopEngine() {
        return ApiClient.post(ENGINE_API_BASE + "/stop", null, String.class);
    }

    /**
     * Parse a string response to determine the engine status
     *
     * @param response The response string from the API
     * @return The corresponding EngineStatus enum
     */
    public static EngineStatus parseEngineStatus(String response) {
        if (response == null) {
            return EngineStatus.STOPPED;
        }

        String statusStr = response.toUpperCase();
        if (statusStr.contains("RUNNING")) {
            return EngineStatus.RUNNING;
        } else if (statusStr.contains("STARTING")) {
            return EngineStatus.STARTING;
        } else if (statusStr.contains("STOPPING")) {
            return EngineStatus.STOPPING;
        } else if (statusStr.contains("KILLED")) {
            return EngineStatus.KILLED;
        } else {
            return EngineStatus.STOPPED;
        }
    }
}
