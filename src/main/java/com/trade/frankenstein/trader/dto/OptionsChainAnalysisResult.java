package com.trade.frankenstein.trader.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class OptionsChainAnalysisResult {
    private String analysisType;
    private Map<String, Object> analysisData;
    private LocalDateTime timestamp;
    private String status;
    private String error;
    private double version;

    // Constructors
    public OptionsChainAnalysisResult() {
        this.timestamp = LocalDateTime.now();
        this.status = "SUCCESS";
        this.version = 1.0;
    }

    public OptionsChainAnalysisResult(String analysisType, Map<String, Object> analysisData) {
        this();
        this.analysisType = analysisType;
        this.analysisData = analysisData;
    }

    public OptionsChainAnalysisResult(String analysisType, Map<String, Object> analysisData, String error) {
        this(analysisType, analysisData);
        this.error = error;
        this.status = "ERROR";
    }

    // Getters and Setters
    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public Map<String, Object> getAnalysisData() {
        return analysisData;
    }

    public void setAnalysisData(Map<String, Object> analysisData) {
        this.analysisData = analysisData;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
        if (error != null) {
            this.status = "ERROR";
        }
    }

    public double getVersion() {
        return version;
    }

    public void setVersion(double version) {
        this.version = version;
    }

    // Utility methods
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    @Override
    public String toString() {
        return "OptionsChainAnalysisResult{" +
                "analysisType='" + analysisType + '\'' +
                ", status='" + status + '\'' +
                ", timestamp=" + timestamp +
                ", hasData=" + (analysisData != null && !analysisData.isEmpty()) +
                '}';
    }
}
