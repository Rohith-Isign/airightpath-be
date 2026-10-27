package com.rightpath.filter;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.config.AuthProperties;
import com.rightpath.error.ApiError;
import com.rightpath.error.ErrorCodes;
import com.rightpath.service.RateLimitService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Caps how often one address may try to log in or ask for / guess a one-time code.
 * The per-account lockout stops one account being guessed; this stops one address
 * walking through many accounts.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimits;
    private final AuthProperties props;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(RateLimitService rateLimits, AuthProperties props, ObjectMapper objectMapper) {
        this.rateLimits = rateLimits;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    private static String scopeOf(String path) {
        return switch (path) {
        case "/api/login" -> "login";
        case "/api/register" -> "register";
        case "/api/generate-otp", "/api/validate-otp", "/api/update-password" -> "otp";
        default -> null;
        };
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.getRateLimit().isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || scopeOf(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String scope = scopeOf(request.getRequestURI());
        AuthProperties.RateLimit cfg = props.getRateLimit();
        int limit = switch (scope) {
        case "login" -> cfg.getLoginPerWindow();
        case "register" -> cfg.getRegisterPerWindow();
        default -> cfg.getOtpPerWindow();
        };
        Duration window = Duration.ofMinutes(cfg.getWindowMinutes());

        if (!rateLimits.tryAcquire(scope + ":" + clientAddress(request), limit, window)) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(window.toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(ErrorCodes.RATE_LIMITED,
                    "Too many requests from this address. Please try again later.", request.getRequestURI(), null));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Behind a load balancer the socket peer is the balancer, so everyone would share one
     * bucket. When told to trust it, use the address the balancer appended last; entries
     * before that are whatever the client claimed and cannot be believed.
     */
    private String clientAddress(HttpServletRequest request) {
        if (props.getRateLimit().isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
