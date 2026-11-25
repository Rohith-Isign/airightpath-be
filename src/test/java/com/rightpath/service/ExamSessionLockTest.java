package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.rightpath.service.state.SharedStateStore;

/** The same exam could run in two browsers at once. One tab holds it now; a reload of that tab keeps it. */
class ExamSessionLockTest {

    /** A store without expiry, enough to test who holds the lease. */
    static final class MapStore implements SharedStateStore {
        final Map<String, String> map = new ConcurrentHashMap<>();
        public boolean setIfAbsent(String key, String value, Duration ttl) { return map.putIfAbsent(key, value) == null; }
        public Optional<String> get(String key) { return Optional.ofNullable(map.get(key)); }
        public void set(String key, String value, Duration ttl) { map.put(key, value); }
        public long increment(String key, Duration ttl) { return 0; }
        public void delete(String key) { map.remove(key); }
    }

    private final MapStore store = new MapStore();
    private final ExamSessionLock lock = new ExamSessionLock(store);
    private static final String TAB_A = "11111111-1111-1111-1111-111111111111";
    private static final String TAB_B = "22222222-2222-2222-2222-222222222222";

    @Test
    void aSecondTabIsRefusedWhileTheFirstHoldsTheExam() {
        assertThat(lock.claim(ExamSessionLock.INTERVIEW, 7L, TAB_A)).isTrue();
        assertThat(lock.claim(ExamSessionLock.INTERVIEW, 7L, TAB_B)).isFalse();
        // The first tab renewing, or reloading with its own id, keeps it.
        assertThat(lock.claim(ExamSessionLock.INTERVIEW, 7L, TAB_A)).isTrue();
        // Another exam is unaffected.
        assertThat(lock.claim(ExamSessionLock.ASSESSMENT, 7L, TAB_B)).isTrue();
    }

    @Test
    void onceReleasedOrLapsedAnotherTabMayOpenIt() {
        lock.claim(ExamSessionLock.ASSESSMENT, 9L, TAB_A);
        lock.release(ExamSessionLock.ASSESSMENT, 9L, TAB_B); // not the holder: no effect
        assertThat(lock.claim(ExamSessionLock.ASSESSMENT, 9L, TAB_B)).isFalse();
        lock.release(ExamSessionLock.ASSESSMENT, 9L, TAB_A);
        assertThat(lock.claim(ExamSessionLock.ASSESSMENT, 9L, TAB_B)).isTrue();
    }

    @Test
    void aSessionIdMustLookLikeOne() {
        assertThat(lock.claim(ExamSessionLock.INTERVIEW, 1L, "")).isFalse();
        assertThat(lock.claim(ExamSessionLock.INTERVIEW, 1L, "x:y")).isFalse();
        assertThat(lock.claim(ExamSessionLock.INTERVIEW, 1L, null)).isFalse();
    }
}
