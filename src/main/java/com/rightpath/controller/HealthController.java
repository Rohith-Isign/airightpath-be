package com.rightpath.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

@RestController
@lombok.extern.slf4j.Slf4j
public class HealthController {

    private static final int MAX_ALLOWED_HEADERS = 2; // adjust limit as needed

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