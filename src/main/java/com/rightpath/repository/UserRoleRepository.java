package com.rightpath.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rightpath.entity.UserRole;
import com.rightpath.rbac.RoleName;
import com.rightpath.rbac.UserRoleName;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
    List<UserRole> findByUser_EmailAndActiveTrue(String email);

    java.util.Optional<UserRole> findByUser_EmailAndRole_NameAndActiveTrue(
            String email,
            com.rightpath.rbac.RoleName roleName
    );

    /**
     * Whether anyone currently holds a role. Used by the super admin bootstrap to
     * decide whether the database still needs a first administrator, without
     * caring which account it turned out to be.
     */
    boolean existsByRole_NameAndActiveTrue(RoleName roleName);

    /**
     * Active role assignments for a batch of users, as (email, role) pairs.
     *
     * <p>A user list that shows each person's role needs this. Reading it per
     * row through {@link #findByUser_EmailAndActiveTrue(String)} would cost one
     * query per user, so a page of 500 accounts would cost 500 queries to fill
     * one column; this answers for the whole page at once.</p>
     *
     * <p>Projected into {@link UserRoleName} rather than returning entities:
     * only the pairing is wanted, and selecting {@code UserRole} would drag its
     * eagerly-fetched {@link com.rightpath.entity.Role} — and that role's
     * permission set — along behind every row.</p>
     *
     * @param emails the users to look up; an empty collection returns nothing
     * @return one entry per active assignment, so a user with two roles appears twice
     */
    @Query("""
            SELECT new com.rightpath.rbac.UserRoleName(ur.user.email, ur.role.name)
            FROM UserRole ur
            WHERE ur.active = true AND ur.user.email IN :emails
            """)
    List<UserRoleName> findActiveRolesByEmails(@Param("emails") Collection<String> emails);
}
