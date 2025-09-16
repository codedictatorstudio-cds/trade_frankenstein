package com.trade.frankenstein.trader.web;

import java.util.List;

public record PagedResponse<T>(List<T> items, String nextPageToken) {
    public static <T> PagedResponse<T> of(List<T> items) {
        return new PagedResponse<>(items, null);
    }
}
