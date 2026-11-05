package com.rightpath.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;

import com.rightpath.config.AuthProperties;
import com.rightpath.repository.UsersRepository;

/**
 * Locks an account after too many wrong passwords in a row.
 *
 * <p>The counters live in the database and change only through single UPDATE
 * statements, so the limit holds however many pods the failures are spread
 * across. Unknown emails are not counted: there is no account to lock, and
 * nothing here may reveal whether one exists.</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UsersRepository users;
    private final AuthProperties.Lockout settings;

    public LoginAttemptService(UsersRepository users, AuthProperties props) {
        this.users = users;
        this.settings = props.getLockout();
    }

    /** Refuse the attempt up front if the account is currently locked. */
    public void requireNotLocked(String email) {
        if (email == null) {
            return;
        }
        users.findByEmail(email).ifPresent(user -> {
            LocalDateTime until = user.getLockedUntil();
            if (until != null && until.isAfter(LocalDateTime.now())) {
                long minutes = Math.max(1, Duration.between(LocalDateTime.now(), until).toMinutes() + 1);
                throw new LockedException("Too many failed login attempts. Try again in " + minutes + " minute(s).");
            }
        });
    }

    /** A wrong password for this email. Locks the account on reaching the limit. */
    public void onFailure(String email) {
        if (email == null || users.recordFailedLogin(email) == 0) {
            return;
        }
        LocalDateTime until = LocalDateTime.now().plusMinutes(settings.getLockMinutes());
        if (users.lockIfOverLimit(email, settings.getMaxFailedAttempts(), until) > 0) {
            log.warn("Account locked until {} after {} failed logins", until, settings.getMaxFailedAttempts());
        }
    }

    public void onSuccess(String email) {
        if (email != null) {
            users.clearLoginFailures(email);
        }
    }
}
