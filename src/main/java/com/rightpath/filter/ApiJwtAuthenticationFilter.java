package com.rightpath.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.error.ApiError;
import com.rightpath.error.ErrorCodes;
import com.rightpath.exceptions.InvalidAccessTokenException;
import com.rightpath.service.impl.AccessTokenService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiJwtAuthenticationFilter.class);

    private final AccessTokenService accessTokenService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    public ApiJwtAuthenticationFilter(AccessTokenService accessTokenService, UserDetailsService userDetailsService,
            ObjectMapper objectMapper) {
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
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
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
            logger.debug("Access token rejected: {}", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError error = ApiError.of(ErrorCodes.AUTH_INVALID_TOKEN, ex.getMessage(),
                    request.getRequestURI(), null);
            objectMapper.writeValue(response.getOutputStream(), error);
        }
    }
}
