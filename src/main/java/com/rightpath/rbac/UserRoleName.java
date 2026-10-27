package com.rightpath.rbac;

/**
 * One active role assignment, flattened to the pair a caller actually reads.
 *
 * <p>Exists so a batch role lookup can project straight out of JPQL instead of
 * loading {@code UserRole} entities: that entity fetches its {@code Role}
 * eagerly, and each {@code Role} carries a permission set, so selecting
 * entities to fill a role column would pull the whole permission graph across
 * for every row on the page.</p>
 *
 * @param userEmail the user holding the role
 * @param role      the role held
 */
public record UserRoleName(String userEmail, RoleName role) {
}
