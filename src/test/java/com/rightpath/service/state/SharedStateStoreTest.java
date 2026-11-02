package com.rightpath.service.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SharedStateStoreTest {

    @Test
    void setIfAbsentTakesTheKeyOnlyOnce() {
        SharedStateStore store = new InMemorySharedStateStore();
        assertTrue(store.setIfAbsent("a", "1", Duration.ofMinutes(1)));
        assertFalse(store.setIfAbsent("a", "1", Duration.ofMinutes(1)));
    }

    @Test
    void anExpiredKeyCanBeTakenAgain() throws Exception {
        SharedStateStore store = new InMemorySharedStateStore();
        assertTrue(store.setIfAbsent("a", "1", Duration.ofMillis(20)));
        Thread.sleep(60);
        assertTrue(store.setIfAbsent("a", "1", Duration.ofMinutes(1)));
        assertEquals(Optional.of("1"), store.get("a"));
    }

    @Test
    void getReturnsNothingOnceExpiredAndAfterDelete() throws Exception {
        SharedStateStore store = new InMemorySharedStateStore();
        store.set("a", "x", Duration.ofMillis(20));
        Thread.sleep(60);
        assertEquals(Optional.empty(), store.get("a"));
        store.set("b", "y", Duration.ofMinutes(1));
        store.delete("b");
        assertEquals(Optional.empty(), store.get("b"));
    }

    @Test
    void incrementCountsUpFromOne() {
        SharedStateStore store = new InMemorySharedStateStore();
        assertEquals(1, store.increment("c", Duration.ofMinutes(1)));
        assertEquals(2, store.increment("c", Duration.ofMinutes(1)));
        assertEquals(1, store.increment("other", Duration.ofMinutes(1)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisBeingDownFallsBackToThisPodsMemoryInsteadOfFailing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenThrow(down);
        when(ops.increment(any())).thenThrow(down);
        when(ops.get(any())).thenThrow(down);

        SharedStateStore store = new RedisSharedStateStore(redis);
        assertTrue(store.setIfAbsent("a", "1", Duration.ofMinutes(1)));
        assertFalse(store.setIfAbsent("a", "1", Duration.ofMinutes(1)), "still de-duplicates locally");
        assertEquals(1, store.increment("n", Duration.ofMinutes(1)));
        assertEquals(2, store.increment("n", Duration.ofMinutes(1)));
        assertEquals(Optional.of("1"), store.get("a"));
    }
}
