package com.rightpath.filter;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global request/response access logging.
 *
 * <p>Runs first in the filter chain so a single correlation id ({@code requestId})
 * is attached to every log line of the request — including Spring Security logs and
 * anything the {@code GlobalExceptionHandler} logs — via SLF4J {@link MDC}. The id is
 * taken from an inbound {@code X-Request-Id} header if present (so it can be traced
 * across services) or generated, and echoed back on the response.</p>
 *
 * <p>Verbosity is controlled entirely from configuration by this logger's level:
 * <ul>
 *   <li>{@code INFO}  → one completed-request line: method, path, status, duration</li>
 *   <li>{@code DEBUG} → also logs the inbound line when the request starts</li>
 *   <li>{@code WARN}+ → silences access logs (errors still logged by the handler)</li>
 * </ul>
 * Set {@code logging.level.com.rightpath.filter.RequestResponseLoggingFilter} (or the
 * broader {@code logging.level.com.rightpath}) per environment.</p>
 *
 * <p>Request/response bodies and sensitive headers (Authorization, Cookie) are never
 * logged.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.currentTimeMillis();
        try {
            if (log.isDebugEnabled()) {
                log.debug("--> {} {} from {}", request.getMethod(), fullPath(request), clientIp(request));
            }
            chain.doFilter(request, response);
        } finally {
            long tookMs = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms)", request.getMethod(), fullPath(request), response.getStatus(), tookMs);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private String fullPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query != null ? request.getRequestURI() + "?" + query : request.getRequestURI();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Skip noisy infrastructure endpoints. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")
                || path.equals("/favicon.ico");
    }
}
