package com.rightpath.service.state;

import java.time.Duration;
import java.util.Optional;

/**
 * Small, expiring key/value state that has to be the same on every pod.
 *
 * <p>Interview traffic for one candidate can land on any replica, so facts such as "this answer was
 * already submitted" or "this is chunk number 7" cannot live in a pod's own memory. Backed by memory
 * on a single replica and by Redis once {@code app.websocket.redis-relay.enabled=true}, the same
 * switch that connects the pods' STOMP brokers.</p>
 */
public interface SharedStateStore {

    /** Stores the value only if the key is free; true when this call took it. Used to drop duplicates. */
    boolean setIfAbsent(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void set(String key, String value, Duration ttl);

    /** Adds one to a counter that starts at zero and returns the new value. The expiry restarts on each call. */
    long increment(String key, Duration ttl);

    void delete(String key);
}
