package com.rightpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Join row granting one role to one user.
 *
 * <p>The index is load-bearing rather than precautionary. Every user listing
 * asks "does this account hold a staff role?" as a correlated {@code EXISTS} on
 * {@code (user_email, active)}; without it MySQL re-scans {@code user_roles} for
 * each candidate row. Declared on the entity because this project has no
 * migration tool — {@code ddl-auto: update} creates it on the next startup.</p>
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(
        name = "user_roles",
        indexes = @Index(name = "idx_user_roles_email_active", columnList = "user_email, active"))
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_email", nullable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
