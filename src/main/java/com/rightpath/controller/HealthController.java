package com.rightpath.controller;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Enumeration;

@RestController
@lombok.extern.slf4j.Slf4j
public class HealthController {

    private static final int MAX_ALLOWED_HEADERS = 2; // adjust limit as needed

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

    @GetMapping("/actuator/health")
    public Object health(HttpServletRequest request, HttpServletResponse response) throws IOException {

        log.debug("Health check request: method={} url={}", request.getMethod(), request.getRequestURL());

        // Count all headers
        Enumeration<String> headerNames = request.getHeaderNames();
        int headerCount = 0;

        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headerCount++;
            // Name only: values can carry credentials (Authorization, Cookie).
            log.trace("Health check header: {}", headerName);
        }

        log.debug("Health check headers received: {}", headerCount);

        // Check if count exceeds threshold
        if (headerCount > MAX_ALLOWED_HEADERS) {
            log.warn("Health check rejected: too many headers ({}, max {})", headerCount, MAX_ALLOWED_HEADERS);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            String errorJson = String.format(
                "{\"error\": \"Too many headers\", \"message\": \"Received %d headers (max allowed: %d)\"}",
                headerCount, MAX_ALLOWED_HEADERS
            );
            response.getWriter().write(errorJson);
            return null;
        }

        // Normal OK response
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        String successJson = String.format("{\"status\": \"UP\", \"headerCount\": %d}", headerCount);
        response.getWriter().write(successJson);
        return null;
    }
}