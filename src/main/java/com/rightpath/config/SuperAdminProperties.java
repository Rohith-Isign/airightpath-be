package com.rightpath.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The super admin account bootstrapped into an empty database at startup.
 *
 * <p>RBAC is seeded from {@link RbacSeedConfig}, but a role nobody holds cannot
 * log in — and every endpoint that could grant it is itself behind a permission.
 * A brand new database therefore has no way in at all unless one account is
 * created out of band, which is what these properties describe.</p>
 *
 * <p>Bound from {@code rightpath.security.super-admin} in
 * {@code application-<profile>.yml}. Keep the real password in the environment
 * ({@code SUPER_ADMIN_PASSWORD}) rather than committed in the yml — the yml
 * should only carry the placeholder that reads it.</p>
 */
@ConfigurationProperties(prefix = "rightpath.security.super-admin")
public class SuperAdminProperties {

    /** Set false to skip the bootstrap entirely, e.g. once the account is managed elsewhere. */
    private boolean enabled = true;

    /** Login email, and the primary key of the created row. Blank skips the bootstrap. */
    private String email;

    /** Plain text here; stored BCrypt-hashed. Blank skips the bootstrap. */
    private String password;

    private String firstName = "Super";

    private String lastName = "Admin";

    /**
     * {@code users.mobile_number} is NOT NULL and unique, so the bootstrap needs
     * one. If it is already taken by a real account the bootstrap is skipped
     * rather than failing startup.
     */
    private String mobileNumber = "0000000000";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }
}
