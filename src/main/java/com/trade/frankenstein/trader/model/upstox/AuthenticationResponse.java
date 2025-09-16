package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@Document("authentication_response")
@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationResponse {

    @Id
    private String id;

    private UpstoxAuthResponse response;

    private Instant createdDate;

}
