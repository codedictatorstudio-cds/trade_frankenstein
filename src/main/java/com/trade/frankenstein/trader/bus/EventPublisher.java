package com.trade.frankenstein.trader.bus;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Properties;

@Service
@Slf4j
public class EventPublisher {
    private final Producer<String, String> producer;

    @Autowired
    public EventPublisher(Environment env) throws IOException {
        Properties p = KafkaPropertiesHelper.loadProducerProps();
        this.producer = KafkaProducerFactory.get(p);
        log.info("Kafka producer bootstrap.servers={}", p.getProperty("bootstrap.servers"));
    }

    public void publish(String topic, String key, String json) {
        if (json == null) json = "{}";
        producer.send(new ProducerRecord<>(topic, key, json), (m, e) -> {
            if (e == null) { log.debug("kafka sent topic={} partition={} offset={}", m.topic(), m.partition(), m.offset()); }
            else { log.warn("kafka send failed topic={} key={} cause={}", topic, key, e.toString()); }
        });
    }
}