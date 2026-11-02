package com.rightpath.service.state;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pod-local store with expiry. Correct for one replica, and the fallback when Redis is unreachable.
 */
public class InMemorySharedStateStore implements SharedStateStore {

    private static final int PURGE_EVERY_WRITES = 500;

    private record Entry(String value, long expiresAtNanos) {
        boolean expired(long now) {
            return now - expiresAtNanos >= 0;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long now = System.nanoTime();
        boolean[] took = { false };
        entries.compute(key, (k, current) -> {
            if (current == null || current.expired(now)) {
                took[0] = true;
                return new Entry(value, now + ttl.toNanos());
            }
            return current;
        });
        purgeSometimes(now);
        return took[0];
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.expired(System.nanoTime())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        long now = System.nanoTime();
        entries.put(key, new Entry(value, now + ttl.toNanos()));
        purgeSometimes(now);
    }

    @Override
    public long increment(String key, Duration ttl) {
        long now = System.nanoTime();
        long[] next = { 0 };
        entries.compute(key, (k, current) -> {
            long base = (current == null || current.expired(now)) ? 0 : Long.parseLong(current.value());
            next[0] = base + 1;
            return new Entry(Long.toString(next[0]), now + ttl.toNanos());
        });
        purgeSometimes(now);
        return next[0];
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    private void purgeSometimes(long now) {
        if (writes.incrementAndGet() % PURGE_EVERY_WRITES == 0) {
            entries.values().removeIf(e -> e.expired(now));
        }
    }
}
