package com.rightpath.websocket;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.rightpath.exceptions.InvalidAccessTokenException;
import com.rightpath.service.MobilePairingService;
import com.rightpath.service.impl.AccessTokenService;

import io.jsonwebtoken.Claims;

/**
 * WebSocket handshake interceptor that validates JWT tokens.
 *
 * Token can be provided via:
 * 1. Query parameter 'token' for JWT authentication.
 * 2. Authorization header: Bearer xxx.
 * 3. Query parameter 'mobileToken' for mobile pairing (no JWT validation).
 *
 * On successful authentication, user info is stored in WebSocket session attributes.
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    public static final String USER_EMAIL_ATTR = "userEmail";
    public static final String USER_AUTHORITIES_ATTR = "userAuthorities";
    public static final String AUTHENTICATED_ATTR = "authenticated";
    public static final String MOBILE_TOKEN_ATTR = "mobileToken";

    private final AccessTokenService accessTokenService;
    private final MobilePairingService pairingService;

    public WebSocketAuthInterceptor(AccessTokenService accessTokenService, MobilePairingService pairingService) {
        this.accessTokenService = accessTokenService;
        this.pairingService = pairingService;
    }

//    @Override
//    public boolean beforeHandshake(
//            ServerHttpRequest request,
//            ServerHttpResponse response,
//            WebSocketHandler wsHandler,
//            Map<String, Object> attributes) {
//
//        // 1. Check for mobile pairing token (no JWT validation)
//        String mobileToken = extractMobileToken(request);
//        if (mobileToken != null && !mobileToken.isBlank()) {
//            log.debug("Mobile WebSocket connection with token: {}", mobileToken);
//            attributes.put(MOBILE_TOKEN_ATTR, mobileToken);
//            return true; // allow handshake without JWT
//        }
//
//        // 2. Otherwise, proceed with normal JWT authentication
//        String token = extractToken(request);
//
//        if (token == null || token.isBlank()) {
//            log.warn("WebSocket connection rejected: No token provided. URI={}", request.getURI());
//            response.setStatusCode(HttpStatus.UNAUTHORIZED);
//            return false;
//        }
//
//        try {
//            Claims claims = accessTokenService.validateAndGetClaims(token);
//            String userEmail = claims.getSubject();
//            String authorities = (String) claims.get("authorities");
//
//            // Store user info in session attributes for use by handlers
//            attributes.put(USER_EMAIL_ATTR, userEmail);
//            attributes.put(USER_AUTHORITIES_ATTR, authorities != null ? authorities : "");
//            attributes.put(AUTHENTICATED_ATTR, true);
//
//            log.info("WebSocket connection authenticated for user: {}", userEmail);
//            return true;
//
//        } catch (InvalidAccessTokenException e) {
//            log.warn("WebSocket connection rejected: Invalid token. URI={}, reason={}",
//                    request.getURI(), e.getMessage());
//            response.setStatusCode(HttpStatus.UNAUTHORIZED);
//            return false;
//        } catch (Exception e) {
//            log.error("WebSocket authentication error. URI={}", request.getURI(), e);
//            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
//            return false;
//        }
//    }
    
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        // Extract tokens
        String mobileToken = extractMobileToken(request);
        String token = extractToken(request);

        if (token == null || token.isBlank()) {
            // The candidate's phone holds no login: all it has is the token in the QR code. It used to be
            // refused here, so the phone could never join the signalling socket and the room check
            // could not tell the desktop it had passed. A phone is let in only on a pairing the
            // signed-in candidate registered, still open and unexpired — and it is NOT marked
            // authenticated, so the interview handlers, which need the candidate's email, still refuse it.
            if (mobileToken != null && !mobileToken.isBlank() && pairingService.resolveOrNull(mobileToken) != null) {
                attributes.put(MOBILE_TOKEN_ATTR, mobileToken);
                attributes.put(AUTHENTICATED_ATTR, false);
                log.info("WebSocket accepted for a paired phone (no login)");
                return true;
            }
            log.warn("WebSocket connection rejected: no JWT and no open phone pairing. URI={}",
                    request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            // ✅ Validate JWT
            Claims claims = accessTokenService.validateAndGetClaims(token);
            String userEmail = claims.getSubject();
            String authorities = (String) claims.get("authorities");

            // ✅ Store authenticated user in session
            attributes.put(USER_EMAIL_ATTR, userEmail);
            attributes.put(USER_AUTHORITIES_ATTR, authorities != null ? authorities : "");
            attributes.put(AUTHENTICATED_ATTR, true);

            // ✅ Optionally store mobileToken (but NOT for authentication)
            if (mobileToken != null && !mobileToken.isBlank()) {
                attributes.put(MOBILE_TOKEN_ATTR, mobileToken);
            }

            log.info("WebSocket authenticated for user: {}", userEmail);
            return true;

        } catch (InvalidAccessTokenException e) {
            log.warn("WebSocket connection rejected: Invalid token. URI={}, reason={}",
                    request.getURI(), e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;

        } catch (Exception e) {
            log.error("WebSocket authentication error. URI={}", request.getURI(), e);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No action needed after handshake
    }

    /**
     * Extracts mobile pairing token from query parameter 'mobileToken'.
     */
    private String extractMobileToken(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query != null) {
            var params = UriComponentsBuilder.fromUriString("?" + query).build().getQueryParams();
            String token = params.getFirst("mobileToken");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }

        // For SockJS connections, try to get from servlet request
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = servletRequest.getServletRequest().getParameter("mobileToken");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return null;
    }

    /**
     * Extracts JWT token from request.
     * Priority: 1) Query parameter 'token', 2) Authorization header
     */
    private String extractToken(ServerHttpRequest request) {
        // Try query parameter first (preferred for WebSocket)
        String query = request.getURI().getQuery();
        if (query != null) {
            var params = UriComponentsBuilder.fromUriString("?" + query).build().getQueryParams();
            String token = params.getFirst("token");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }

        // Try Authorization header
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // For SockJS connections, try to get from servlet request
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = servletRequest.getServletRequest().getParameter("token");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }

        return null;
    }
}