package com.rightpath.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@lombok.extern.slf4j.Slf4j
public class HealthController {

    private static final int DB_CHECK_TIMEOUT_SECONDS = 2;

    private final javax.sql.DataSource dataSource;

    public HealthController(javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Public database check: 200 when a connection can be obtained and is valid, otherwise 503.
     * Outside /api/**, so no login applies. The failure reason is logged, never returned.
     */
    @GetMapping("/health-db")
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> healthDb() {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            if (connection.isValid(DB_CHECK_TIMEOUT_SECONDS)) {
                return org.springframework.http.ResponseEntity.ok(java.util.Map.of("status", "healthy"));
            }
            log.warn("Database health check failed: connection is not valid");
        } catch (java.sql.SQLException e) {
            log.warn("Database health check failed", e);
        }
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(java.util.Map.of("status", "unhealthy"));
    }

    /** Public liveness check: answers as long as the application can serve requests. */
    @GetMapping("/api/health")
    public java.util.Map<String, String> apiHealth() {
        return java.util.Map.of("status", "UP");
    }

    /** Kept for monitors that already point here. Answers like /api/health, whatever headers are sent. */
    @GetMapping("/actuator/health")
    public java.util.Map<String, String> health() {
        return java.util.Map.of("status", "UP");
    }
}
