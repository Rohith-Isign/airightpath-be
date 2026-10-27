package com.rightpath.service.impl;

import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.rightpath.dto.LoginRequest;
import com.rightpath.dto.LoginResponse;
import com.rightpath.dto.UserInfo;
import com.rightpath.util.RefreshCookieFactory;
import com.rightpath.util.ThreadLocalUserContext;
import com.rightpath.entity.Users;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class LoginService {

    private final AuthenticationManager authenticationManager;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieFactory refreshCookieFactory;

    public LoginService(
            AuthenticationManager authenticationManager,
            AccessTokenService accessTokenService,
            RefreshTokenService refreshTokenService,
            RefreshCookieFactory refreshCookieFactory
    ) {
        this.authenticationManager = authenticationManager;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    public LoginResult login(LoginRequest request, HttpServletRequest http) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        UserDetails userDetails = (UserDetails) auth.getPrincipal();

        var issuedAccess = accessTokenService.issueAccessToken(userDetails);
        var issuedRefresh = refreshTokenService.createNewSessionToken(
                userDetails.getUsername(),
                http.getRemoteAddr(),
                http.getHeader("User-Agent")
        );

        ResponseCookie cookie = refreshCookieFactory.build(issuedRefresh.rawToken());

    UserInfo user = mapUserInfo();

        // Authorities were already resolved during authentication
        // (UserDetailsServiceImpl.loadUserByUsername); reuse them instead of
        // re-querying RBAC, which previously ran the resolve loop twice per login.
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (GrantedAuthority granted : userDetails.getAuthorities()) {
            String authority = granted.getAuthority();
            if (authority.startsWith("ROLE_")) {
                roles.add(authority.substring("ROLE_".length()));
            } else {
                permissions.add(authority);
            }
        }

        LoginResponse body = new LoginResponse(
                issuedAccess.token(),
                "Bearer",
                issuedAccess.expiresInSeconds(),
                user,
                roles,
                permissions
        );

        return new LoginResult(body, cookie);
    }

    private static UserInfo mapUserInfo() {
        Users u = ThreadLocalUserContext.getUserEntity();
        if (u == null) {
            return new UserInfo(null, null, null, null, null);
        }
        return new UserInfo(
                u.getEmail(),
                u.getFirstName(),
                u.getLastName(),
                u.getMobileNumber(),
                u.getAlternativeMobileNumber()
        );
    }

    public record LoginResult(LoginResponse body, ResponseCookie refreshCookie) {
    }
}
