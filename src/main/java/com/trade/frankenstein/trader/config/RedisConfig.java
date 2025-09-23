package com.trade.frankenstein.trader.config;

import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.core.RedisFastStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@ConditionalOnProperty(name = "trade.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // host/port picked from spring.data.redis.* properties
        return new LettuceConnectionFactory();
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    @Bean
    public FastStateStore fastStateStore(StringRedisTemplate template) {
        return new RedisFastStateStore(template, "tf:");
    }
}
