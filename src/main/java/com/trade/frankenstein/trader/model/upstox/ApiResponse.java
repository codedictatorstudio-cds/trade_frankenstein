package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ApiResponse<T> {
    public String status;
    public T data;
    public Meta metadata;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Meta {
        public Integer latency;
    }
}
