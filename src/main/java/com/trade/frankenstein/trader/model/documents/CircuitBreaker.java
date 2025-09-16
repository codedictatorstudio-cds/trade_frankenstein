package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("circuit_breaker")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircuitBreaker {

    @Id
    private String id;

    private boolean tripped;
    private String reason;

    private Instant trippedAt;
    private Instant lastResetAt;
    private String lastResetBy;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
}
