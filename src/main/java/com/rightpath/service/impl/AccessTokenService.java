package com.rightpath.service.impl;

import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.rightpath.config.AuthProperties;
import com.rightpath.exceptions.InvalidAccessTokenException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AccessTokenService {

    private final AuthProperties props;

    /** fallback to existing V1 secret to avoid breaking deployments */
    @Value("${jwt.token:}")
    private String legacySecret;

    public AccessTokenService(AuthProperties props) {
        this.props = props;
    }

    public record IssuedAccessToken(String token, long expiresInSeconds) {
    }

    public IssuedAccessToken issueAccessToken(UserDetails userDetails) {
        String authorities = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.joining(","));

        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.getJwt().getAccessTtlMinutes() * 60);

        String jwt = Jwts.builder()
                .setIssuer(props.getJwt().getIssuer())
                .setAudience(props.getJwt().getAudience())
                .setSubject(userDetails.getUsername())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now))
                .setNotBefore(Date.from(now))
                .setExpiration(Date.from(exp))
                .addClaims(Map.of("authorities", authorities))
                .signWith(getKey(), SignatureAlgorithm.HS512)
                .compact();

        log.debug("Issued access token for {}", userDetails.getUsername());
        return new IssuedAccessToken(jwt, exp.getEpochSecond() - now.getEpochSecond());
    }

    public Claims validateAndGetClaims(String token) {
        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .setSigningKey(getKey())
                    .requireIssuer(props.getJwt().getIssuer())
                    .build()
                    .parseClaimsJws(token);

            Claims claims = jws.getBody();

            // Enforce audience
            String aud = claims.getAudience();
            if (aud == null || !aud.equals(props.getJwt().getAudience())) {
                throw new InvalidAccessTokenException("Invalid audience");
            }

            long skew = props.getJwt().getClockSkewSeconds();
            Instant now = Instant.now();

            Date nbf = claims.getNotBefore();
            if (nbf != null && nbf.toInstant().isAfter(now.plusSeconds(skew))) {
                throw new InvalidAccessTokenException("Token not active yet");
            }

            Date exp = claims.getExpiration();
            if (exp != null && exp.toInstant().isBefore(now.minusSeconds(skew))) {
                throw new InvalidAccessTokenException("Token expired");
            }

            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Access token rejected: {}", e.getClass().getSimpleName());
            throw new InvalidAccessTokenException("Invalid access token", e);
        }
    }

    public String getSubject(String token) {
        return validateAndGetClaims(token).getSubject();
    }

    private Key getKey() {
        String configured = props.getJwt().getSecret();
        String secret = (configured != null && !configured.isBlank()) ? configured : legacySecret;
        if (secret == null || secret.isBlank()) {
            log.error("JWT secret is not configured");
            throw new IllegalStateException("JWT secret not configured");
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
}
