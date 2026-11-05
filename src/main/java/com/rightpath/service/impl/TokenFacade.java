package com.rightpath.service.impl;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import com.rightpath.dto.AccessTokenResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TokenFacade {

    private final UserDetailsService userDetailsService;
    private final AccessTokenService accessTokenService;

    public TokenFacade(UserDetailsService userDetailsService, AccessTokenService accessTokenService) {
        this.userDetailsService = userDetailsService;
        this.accessTokenService = accessTokenService;
    }

    public AccessTokenResponse issueAccessTokenForSubject(String subject) {
        log.debug("Issuing access token for {}", subject);
        UserDetails user = userDetailsService.loadUserByUsername(subject);
        var issued = accessTokenService.issueAccessToken(user);
        return new AccessTokenResponse(issued.token(), "Bearer", issued.expiresInSeconds());
    }
}
