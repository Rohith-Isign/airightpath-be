package com.rightpath.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.rightpath.entity.RateLimitBucket;
import com.rightpath.repository.RateLimitBucketRepository;

/**
 * A fixed-window counter shared by every pod through the database.
 *
 * <p>Each step is one conditional UPDATE, so two pods counting the same caller
 * cannot lose a hit. If the store itself fails the caller is let through: a
 * database hiccup must not lock everyone out of logging in.</p>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimitBucketRepository buckets;

    public RateLimitService(RateLimitBucketRepository buckets) {
        this.buckets = buckets;
    }

    /** Counts this call and says whether it is within {@code limit} per {@code window}. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minus(window);
            if (buckets.hit(key, cutoff) == 0 && buckets.restart(key, cutoff, now) == 0) {
                try {
                    buckets.saveAndFlush(new RateLimitBucket(key, now, 1));
                    return true;
                } catch (DataIntegrityViolationException raced) {
                    // Another pod created it a moment ago; count against theirs.
                    buckets.hit(key, cutoff);
                }
            }
            return buckets.findById(key).map(b -> b.getHits() <= limit).orElse(true);
        } catch (RuntimeException e) {
            log.warn("Rate limit store unavailable; allowing the request: {}", e.getMessage());
            return true;
        }
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void purge() {
        try {
            buckets.deleteOlderThan(LocalDateTime.now().minusHours(2));
        } catch (RuntimeException e) {
            log.warn("Could not purge rate limit buckets: {}", e.getMessage());
        }
    }
}
