package com.rightpath.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtService {

	@Value("${jwt.token}") // JWT secret key from application properties
	private String JWT_SECRET;

	/**
	 * Generates a JWT token for the given user details.
	 *
	 * @param userDetails The user's details, including username and roles.
	 * @return A signed JWT token.
	 */
	public String generateToken(UserDetails userDetails) {
		// Concatenate authorities as a comma-separated string
		String authorities = userDetails.getAuthorities().stream().map(GrantedAuthority::getAuthority)
				.collect(Collectors.joining(","));

		log.debug("Generating JWT for {}", userDetails.getUsername());
		return Jwts.builder().setSubject(userDetails.getUsername()).claim("authorities", authorities)
				.setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 654000000))
				.signWith(getKey(), SignatureAlgorithm.HS512).compact();
	}

	/**
	 * Retrieves the signing key for the JWT token.
	 *
	 * @return A Key object generated from the JWT secret.
	 */
	public Key getKey() {
		return Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
	}

	/**
	 * Extracts claims from the given token.
	 *
	 * @param token The JWT token.
	 * @return Claims contained in the token.
	 */
	public Claims getClaimsFromToken(String token) {
		JwtParser parser = Jwts.parserBuilder().setSigningKey(getKey()).build(); // Updated parser creation
		return parser.parseClaimsJws(token).getBody();
	}

	/**
	 * Extracts authorities/roles from the token and converts them into
	 * GrantedAuthority objects.
	 *
	 * @param token The JWT token.
	 * @return A collection of GrantedAuthority objects.
	 */
	public Collection<? extends GrantedAuthority> getAuthoritiesFromToken(String token) {
		Claims claims = getClaimsFromToken(token);
		String authoritiesString = claims.get("authorities", String.class);
		if (authoritiesString == null) {
			return Collections.emptyList();
		}

		List<GrantedAuthority> authorities = new ArrayList<>();
		for (String authority : authoritiesString.split(",")) {
			authorities.add(new SimpleGrantedAuthority(authority));
		}
		return authorities;
	}

	/**
	 * Validates the token for its username and expiration.
	 *
	 * @param token    The JWT token.
	 * @param username The expected username.
	 * @return True if the token is valid, false otherwise.
	 */
	public Boolean validateToken(String token, String username) {
		final String extractedUsername = extractEmailname(token);
		boolean valid = extractedUsername.equals(username) && !isTokenExpired(token);
		if (!valid) {
			log.warn("JWT validation failed for {}", username);
		}
		return valid;
	}

	/**
	 * Checks if the token is expired.
	 *
	 * @param token The JWT token.
	 * @return True if the token is expired, false otherwise.
	 */
	private Boolean isTokenExpired(String token) {
		return extractExpiration(token).before(new Date());
	}

	/**
	 * Extracts the expiration date from the token.
	 *
	 * @param token The JWT token.
	 * @return The expiration date of the token.
	 */
	public Date extractExpiration(String token) {
		return extractClaim(token, Claims::getExpiration);
	}

	/**
	 * Extracts a specific claim from the token using a resolver function.
	 *
	 * @param token          The JWT token.
	 * @param claimsResolver Function to resolve the desired claim.
	 * @return The resolved claim.
	 */
	public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
		final Claims claims = extractAllClaims(token);
		return claimsResolver.apply(claims);
	}

	/**
	 * Extracts the username (subject) from the token.
	 *
	 * @param token The JWT token.
	 * @return The username stored in the token.
	 */
	public String extractEmailname(String token) {
		return extractClaim(token, Claims::getSubject);
	}

	/**
	 * Extracts all claims from the token.
	 *
	 * @param token The JWT token.
	 * @return All claims contained in the token.
	 */
	private Claims extractAllClaims(String token) {
		JwtParser parser = Jwts.parserBuilder().setSigningKey(getKey()).build();
		return parser.parseClaimsJws(token).getBody();

	}
}
