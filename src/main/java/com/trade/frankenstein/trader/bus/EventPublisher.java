package com.trade.frankenstein.trader.bus;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Service
@Slf4j
public class EventPublisher {

    private final Producer<String, String> producer;

    @Autowired
    public EventPublisher(Environment env) throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/producer.properties")) {
            p.load(in);
        }
        String brokers = System.getenv("KAFKA_BROKERS");
        if (brokers != null && brokers.trim().length() > 0) p.put("bootstrap.servers", brokers);
        this.producer = KafkaProducerFactory.get(p);
    }

    public void publish(String topic, String key, String json) {
        if (json == null) json = "{}";
        producer.send(new ProducerRecord<String, String>(topic, key, json),
                new Callback() {
                    public void onCompletion(RecordMetadata m, Exception e) {
                        if (e != null) {
                            log.info("published to topic {} partition {} offset {}",
                                    m.topic(), m.partition(), m.offset());
                        } else {
                            log.error("publish failed {}", e.getMessage());
                        }
                    }
                });
    }
}