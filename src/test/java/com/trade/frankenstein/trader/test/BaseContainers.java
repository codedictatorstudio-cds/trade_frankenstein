package com.trade.frankenstein.trader.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(SpringExtension.class)
public abstract class BaseContainers {

    // MongoDB 6.x is stable and fine for Spring Data Mongo
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

    // Redis 7-alpine is lightweight
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        MONGO.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) {
        reg.add("spring.data.mongodb.uri", MONGO::getConnectionString);
        reg.add("spring.data.redis.host", () -> REDIS.getHost());
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
