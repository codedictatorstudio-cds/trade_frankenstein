package com.trade.frankenstein.trader.model.documents;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Data
@Document(collection = "outbox_events")
public class OutboxEvent {
    @Id
    private String id;

    @Field("topic")
    private String topic;

    @Field("key")
    private String key;

    @Field("payload")
    private String payload;

    @Field("created_at")
    private Instant createdAt = Instant.now();

    @Field("published")
    private boolean published = false;

    @Field("published_at")
    private Instant publishedAt;
}
