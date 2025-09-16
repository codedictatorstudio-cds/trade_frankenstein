package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@Document("upstox_auth_response")
@AllArgsConstructor
@NoArgsConstructor
public class UpstoxAuthResponse {

    private String id;

    private String email;

    private List<String> exchanges;

    private List<String> products;

    private String broker;

    private String user_id;

    private String user_name;

    private List<String> order_types;

    private String user_type;

    private Boolean poa;

    private Boolean is_active;

    private String access_token;

    private String extended_token;
}
