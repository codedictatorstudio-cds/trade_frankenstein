package com.trade.frankenstein.trader.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedOptionChainDTO<T> {
    private List<T> data;
    private int currentPage;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
    private OptionChainAnalyticsDTO analytics;

    // Sorting and filtering metadata
    private String sortBy;
    private String sortDirection;
    private List<String> appliedFilters;

    // Performance metadata
    private long queryTimeMs;
    private String cacheStatus; // HIT, MISS, PARTIAL
    private long cacheExpiryMs;

    // Data quality indicators
    private double dataCompleteness; // Percentage of complete records
    private int missingDataCount;
    private boolean hasStaleData;
    private java.time.Instant lastRefresh;

    // Navigation helpers
    public boolean isFirstPage() {
        return currentPage == 0;
    }

    public boolean isLastPage() {
        return currentPage >= totalPages - 1;
    }

    public int getNextPage() {
        return hasNext ? currentPage + 1 : currentPage;
    }

    public int getPreviousPage() {
        return hasPrevious ? currentPage - 1 : currentPage;
    }

    public double getCompletionPercentage() {
        if (totalElements == 0) return 100.0;
        return ((double) (totalElements - missingDataCount) / totalElements) * 100.0;
    }

    public boolean isHighQuality() {
        return dataCompleteness >= 0.95 && !hasStaleData;
    }

    // Create page info summary
    public String getPageInfo() {
        if (totalElements == 0) return "No results";

        long startItem = (long) currentPage * pageSize + 1;
        long endItem = Math.min(startItem + pageSize - 1, totalElements);

        return String.format("Showing %d-%d of %d results", startItem, endItem, totalElements);
    }
}
