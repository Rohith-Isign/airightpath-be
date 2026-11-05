package com.rightpath.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.config.AuthProperties;
import com.rightpath.error.ApiError;
import com.rightpath.error.ErrorCodes;
import com.rightpath.exceptions.InvalidAccessTokenException;
import com.rightpath.service.impl.AccessTokenService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiJwtAuthenticationFilter.class);

    private final AccessTokenService accessTokenService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Value("${app.cors.allowed-origins:}")
    private String[] allowedOrigins = new String[0];

    public ApiJwtAuthenticationFilter(AccessTokenService accessTokenService, UserDetailsService userDetailsService,
            ObjectMapper objectMapper, AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.accessTokenService = accessTokenService;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Allow CORS preflight to pass through.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }

        // Public endpoints (no JWT required).
        return path.equals("/api/login")
                || path.equals("/api/register")
                || path.equals("/api/refresh")
                || path.equals("/api/logout")
                || path.equals("/api/health")
                || path.equals("/api/healthcheck")
        || path.startsWith("/api/mobile/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean fromCookie = false;
        String token;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            token = accessCookie(request);
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }
            fromCookie = true;
            // A browser attaches the cookie to any request it is made to send, so a
            // cookie-authenticated change must come from one of our own pages.
            if (!SAFE_METHODS.contains(request.getMethod().toUpperCase()) && !originAllowed(request)) {
                logger.warn("Cookie-authenticated {} {} refused: Origin not allowed", request.getMethod(),
                        request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), ApiError.of(ErrorCodes.AUTH_FORBIDDEN,
                        "Request origin is not allowed.", request.getRequestURI(), null));
                return;
            }
        }

        try {
            String subject = accessTokenService.getSubject(token);
            if (subject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails user = userDetailsService.loadUserByUsername(subject);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user.getUsername(),
                        null,
                        user.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
        } catch (InvalidAccessTokenException ex) {
            if (fromCookie) {
                // A stale cookie must not break endpoints that need no login; the ones
                // that do are refused further down for lack of authentication.
                logger.debug("Access cookie rejected: {}", ex.getMessage());
                filterChain.doFilter(request, response);
                return;
            }
            logger.debug("Access token rejected: {}", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError error = ApiError.of(ErrorCodes.AUTH_INVALID_TOKEN, ex.getMessage(),
                    request.getRequestURI(), null);
            objectMapper.writeValue(response.getOutputStream(), error);
        }
    }

    private String accessCookie(HttpServletRequest request) {
        if (!authProperties.getAccess().isCookieEnabled() || request.getCookies() == null) {
            return null;
        }
        String name = authProperties.getAccess().getCookieName();
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return Arrays.stream(allowedOrigins).anyMatch(a -> a.trim().equalsIgnoreCase(origin));
    }
}
