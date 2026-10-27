package com.rightpath.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How many times one caller has used one limited endpoint in the current window.
 * Kept in the database so the limit holds across pods.
 */
@Entity
@Table(name = "rate_limit_bucket")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitBucket {

    /** "scope:caller", e.g. "login:203.0.113.7". */
    @Id
    @Column(name = "bucket_key", length = 200)
    private String key;

    @Column(nullable = false)
    private LocalDateTime windowStart;

    @Column(nullable = false)
    private int hits;
}
