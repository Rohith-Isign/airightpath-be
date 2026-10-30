package com.rightpath.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rightpath.security.v2")
public class AuthProperties {

    private final Jwt jwt = new Jwt();
    private final Refresh refresh = new Refresh();
    private final Access access = new Access();
    private final Lockout lockout = new Lockout();
    private final Otp otp = new Otp();
    private final RateLimit rateLimit = new RateLimit();
    private final WebSocket websocket = new WebSocket();

    public Jwt getJwt() {
        return jwt;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public Access getAccess() {
        return access;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Otp getOtp() {
        return otp;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /** Per-address limits on login and one-time-code endpoints. */
    public static class RateLimit {
        private boolean enabled = true;
        private int windowMinutes = 10;
        private int loginPerWindow = 20;
        private int otpPerWindow = 10;
        private int registerPerWindow = 10;
        /** Set when running behind a load balancer that appends the client address to X-Forwarded-For. */
        private boolean trustForwardedFor = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
        public int getLoginPerWindow() { return loginPerWindow; }
        public void setLoginPerWindow(int loginPerWindow) { this.loginPerWindow = loginPerWindow; }
        public int getOtpPerWindow() { return otpPerWindow; }
        public void setOtpPerWindow(int otpPerWindow) { this.otpPerWindow = otpPerWindow; }
        public int getRegisterPerWindow() { return registerPerWindow; }
        public void setRegisterPerWindow(int registerPerWindow) { this.registerPerWindow = registerPerWindow; }
        public boolean isTrustForwardedFor() { return trustForwardedFor; }
        public void setTrustForwardedFor(boolean trustForwardedFor) { this.trustForwardedFor = trustForwardedFor; }
    }

    public WebSocket getWebsocket() {
        return websocket;
    }

    /** The access token as an HttpOnly cookie, so page scripts (and XSS) never see it. */
    public static class Access {
        private boolean cookieEnabled = true;
        private String cookieName = "access_token";
        private boolean cookieSecure = true;
        /** Strict by default; use Lax or None (with Secure) only if the SPA is served from a different site. */
        private String cookieSamesite = "Strict";
        private String cookiePath = "/";
        /**
         * Keep returning the token in the JSON body while the frontend still reads it
         * from there. Turn off once the frontend relies on the cookie alone.
         */
        private boolean returnTokenInBody = true;

        public boolean isCookieEnabled() { return cookieEnabled; }
        public void setCookieEnabled(boolean cookieEnabled) { this.cookieEnabled = cookieEnabled; }
        public String getCookieName() { return cookieName; }
        public void setCookieName(String cookieName) { this.cookieName = cookieName; }
        public boolean isCookieSecure() { return cookieSecure; }
        public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
        public String getCookieSamesite() { return cookieSamesite; }
        public void setCookieSamesite(String cookieSamesite) { this.cookieSamesite = cookieSamesite; }
        public String getCookiePath() { return cookiePath; }
        public void setCookiePath(String cookiePath) { this.cookiePath = cookiePath; }
        public boolean isReturnTokenInBody() { return returnTokenInBody; }
        public void setReturnTokenInBody(boolean returnTokenInBody) { this.returnTokenInBody = returnTokenInBody; }
    }

    /** Account lockout after repeated failed logins. */
    public static class Lockout {
        private int maxFailedAttempts = 5;
        private long lockMinutes = 15;

        public int getMaxFailedAttempts() { return maxFailedAttempts; }
        public void setMaxFailedAttempts(int maxFailedAttempts) { this.maxFailedAttempts = maxFailedAttempts; }
        public long getLockMinutes() { return lockMinutes; }
        public void setLockMinutes(long lockMinutes) { this.lockMinutes = lockMinutes; }
    }

    /** One-time-password limits. */
    public static class Otp {
        private int maxAttempts = 3;
        /** Minimum gap between two codes for the same contact. */
        private long resendCooldownSeconds = 60;

        public long getResendCooldownSeconds() { return resendCooldownSeconds; }
        public void setResendCooldownSeconds(long resendCooldownSeconds) { this.resendCooldownSeconds = resendCooldownSeconds; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class WebSocket {
        /**
         * Still accept the JWT as a {@code ?token=} query parameter. URLs are logged by
         * proxies and kept in history, so this exists only for clients not yet moved to
         * the access cookie; turn it off once they have.
         */
        private boolean allowQueryToken = true;

        public boolean isAllowQueryToken() { return allowQueryToken; }
        public void setAllowQueryToken(boolean allowQueryToken) { this.allowQueryToken = allowQueryToken; }
    }

    public static class Jwt {
        /** Optional. If blank, fall back to legacy jwt.token */
        private String secret;
        private String issuer;
        private String audience;
        private long accessTtlMinutes = 10;
        private long clockSkewSeconds = 60;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public long getAccessTtlMinutes() {
            return accessTtlMinutes;
        }

        public void setAccessTtlMinutes(long accessTtlMinutes) {
            this.accessTtlMinutes = accessTtlMinutes;
        }

        public long getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(long clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }
    }

    public static class Refresh {
        private long ttlDays = 14;
        private String cookieName = "refresh_token";
        private boolean cookieSecure = true;
        private String cookieSamesite = "Strict";
        private String cookiePath = "/api/auth/refresh";

        public long getTtlDays() {
            return ttlDays;
        }

        public void setTtlDays(long ttlDays) {
            this.ttlDays = ttlDays;
        }

        public String getCookieName() {
            return cookieName;
        }

        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public String getCookieSamesite() {
            return cookieSamesite;
        }

        public void setCookieSamesite(String cookieSamesite) {
            this.cookieSamesite = cookieSamesite;
        }

        public String getCookiePath() {
            return cookiePath;
        }

        public void setCookiePath(String cookiePath) {
            this.cookiePath = cookiePath;
        }
    }
}
