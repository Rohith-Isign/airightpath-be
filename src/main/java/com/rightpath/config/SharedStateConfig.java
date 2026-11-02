package com.rightpath.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.rightpath.service.state.InMemorySharedStateStore;
import com.rightpath.service.state.RedisSharedStateStore;
import com.rightpath.service.state.SharedStateStore;

/**
 * Chooses where cross-pod state lives: Redis when {@code app.websocket.redis-relay.enabled=true},
 * otherwise this pod's memory (correct for a single replica).
 */
@Configuration
public class SharedStateConfig {

    @Bean
    @ConditionalOnProperty(name = "app.websocket.redis-relay.enabled", havingValue = "true")
    public SharedStateStore redisSharedStateStore(StringRedisTemplate redis) {
        return new RedisSharedStateStore(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "app.websocket.redis-relay.enabled", havingValue = "false", matchIfMissing = true)
    public SharedStateStore inMemorySharedStateStore() {
        return new InMemorySharedStateStore();
    }
}
