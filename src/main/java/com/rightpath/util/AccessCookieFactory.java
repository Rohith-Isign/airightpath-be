package com.rightpath.util;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.rightpath.config.AuthProperties;

/** Builds the HttpOnly cookie that carries the access token. */
@Component
public class AccessCookieFactory {

    private final AuthProperties props;

    public AccessCookieFactory(AuthProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.getAccess().isCookieEnabled();
    }

    public String cookieName() {
        return props.getAccess().getCookieName();
    }

    public ResponseCookie build(String accessToken, long ttlSeconds) {
        return base(accessToken).maxAge(ttlSeconds).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        AuthProperties.Access a = props.getAccess();
        return ResponseCookie.from(a.getCookieName(), value)
                .httpOnly(true)
                .secure(a.isCookieSecure())
                .path(a.getCookiePath())
                .sameSite(a.getCookieSamesite());
    }
}
