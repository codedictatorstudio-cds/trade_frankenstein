package com.trade.frankenstein.trader.model.documents;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Lightweight engine and stream logs for diagnostics
 */
@Document("execution_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionLog {

    @Id
    private String id;

    private Instant ts;
    private String level;       // INFO/WARN/ERROR
    private String topic;       // e.g., "engine.tick", "advice.exec"
    private String message;
    private String payload;     // small JSON/text (optional)

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
}
