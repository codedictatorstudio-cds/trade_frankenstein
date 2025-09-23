package com.trade.frankenstein.trader.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContainersUpTest extends BaseContainers {
    @Test
    void containersAreRunning() {
        assertTrue(BaseContainers.MONGO.isRunning(), "Mongo must run");
        assertTrue(BaseContainers.REDIS.isRunning(), "Redis must run");
    }
}
