package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProfileResponse {

    private String status;
    private ProfileData data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ProfileData {
        private String email;
        private List<String> exchanges;
        private List<String> products;
        private String broker;
        private String user_id;
        private String user_name;
        private List<String> order_types;
        private String user_type;
        private boolean poa;
        private boolean ddpi;
        private boolean is_active;
    }
}
