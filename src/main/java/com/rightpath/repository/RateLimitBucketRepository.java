package com.rightpath.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.RateLimitBucket;

public interface RateLimitBucketRepository extends JpaRepository<RateLimitBucket, String> {

    /** Count a hit in the live window. Returns 0 if there is no live window for the key. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE RateLimitBucket b SET b.hits = b.hits + 1 WHERE b.key = :key AND b.windowStart > :cutoff")
    int hit(@Param("key") String key, @Param("cutoff") LocalDateTime cutoff);

    /** Open a fresh window for a key whose last one has run out. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE RateLimitBucket b SET b.hits = 1, b.windowStart = :now WHERE b.key = :key AND b.windowStart <= :cutoff")
    int restart(@Param("key") String key, @Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM RateLimitBucket b WHERE b.windowStart < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
