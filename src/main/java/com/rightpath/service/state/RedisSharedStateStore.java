package com.rightpath.service.state;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed store, shared by every pod.
 *
 * <p>Redis being down must not take an interview down with it, so every call falls back to this
 * pod's own memory and logs a warning. The result is the old single-pod behaviour for as long as Redis
 * is unreachable, not an error on the candidate's screen.</p>
 */
public class RedisSharedStateStore implements SharedStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSharedStateStore.class);
    private static final String PREFIX = "rightpath:state:";

    private final StringRedisTemplate redis;
    private final SharedStateStore fallback = new InMemorySharedStateStore();

    public RedisSharedStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, value, ttl));
        } catch (RuntimeException e) {
            degraded("setIfAbsent", key, e);
            return fallback.setIfAbsent(key, value, ttl);
        }
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + key));
        } catch (RuntimeException e) {
            degraded("get", key, e);
            return fallback.get(key);
        }
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + key, value, ttl);
        } catch (RuntimeException e) {
            degraded("set", key, e);
            fallback.set(key, value, ttl);
        }
    }

    @Override
    public long increment(String key, Duration ttl) {
        try {
            Long value = redis.opsForValue().increment(PREFIX + key);
            redis.expire(PREFIX + key, ttl);
            return value == null ? fallback.increment(key, ttl) : value;
        } catch (RuntimeException e) {
            degraded("increment", key, e);
            return fallback.increment(key, ttl);
        }
    }

    @Override
    public void delete(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (RuntimeException e) {
            degraded("delete", key, e);
        }
        fallback.delete(key);
    }

    private void degraded(String operation, String key, RuntimeException e) {
        log.warn("Shared state store: Redis {} failed for {}; using this pod's memory instead: {}", operation, key,
                e.getMessage());
    }
}
