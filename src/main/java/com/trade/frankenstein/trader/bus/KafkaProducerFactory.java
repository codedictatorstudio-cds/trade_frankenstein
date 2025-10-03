package com.trade.frankenstein.trader.bus;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;

import java.util.Properties;

public final class KafkaProducerFactory {

    private static volatile Producer<String, String> INSTANCE;

    public static Producer<String, String> get(Properties baseProps) {
        baseProps = KafkaPropertiesHelper.loadProducerProps();
        if (INSTANCE == null) {
            synchronized (KafkaProducerFactory.class) {
                if (INSTANCE == null) INSTANCE = new KafkaProducer<String, String>(baseProps);
            }
        }
        return INSTANCE;
    }

    private KafkaProducerFactory() {
    }
}