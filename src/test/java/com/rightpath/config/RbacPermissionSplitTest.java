package com.rightpath.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.rightpath.rbac.PermissionName;

/**
 * Pins the ADMIN / SUPER_ADMIN privilege boundary.
 *
 * <p>Role assignment used to be guarded by {@code USER_UPDATE}, which every
 * candidate holds so they can edit their own profile — so every signed-in
 * candidate could grant themselves SUPER_ADMIN. {@code ROLE_MANAGE} exists to
 * separate "may edit a profile" from "may decide who holds power", and these
 * assertions are what stop it drifting back: the seed builds ADMIN from
 * {@code allOf} minus a deny list, so a new permission is granted to admins by
 * default and only this test would notice a dangerous one being added.</p>
 */
class RbacPermissionSplitTest {

    @Test
    void adminCannotManageRoles() {
        assertFalse(
                RbacSeedConfig.adminPermissions().contains(PermissionName.ROLE_MANAGE),
                "ADMIN must not be able to grant roles — that makes it SUPER_ADMIN");
    }

    @Test
    void adminCannotEnableOrDisableAccounts() {
        EnumSet<PermissionName> admin = RbacSeedConfig.adminPermissions();
        assertFalse(admin.contains(PermissionName.USER_ACTIVATE));
        assertFalse(admin.contains(PermissionName.USER_DEACTIVATE));
    }

    @Test
    void adminCanListAccounts() {
        // Deliberately granted: without it, User Management showed in the
        // sidebar and answered 403.
        assertTrue(RbacSeedConfig.adminPermissions().contains(PermissionName.USER_LIST));
    }

    @Test
    void candidatesCannotManageRolesOrAccounts() {
        EnumSet<PermissionName> user = RbacSeedConfig.userPermissions();
        assertFalse(user.contains(PermissionName.ROLE_MANAGE), "this was the escalation path");
        // Also guards GET /api/admin/rbac/user, which used to accept USER_READ
        // and so let any candidate read anyone's roles — see
        // RbacViewAuthorizationTest.
        assertFalse(user.contains(PermissionName.USER_LIST));
        assertFalse(user.contains(PermissionName.USER_ACTIVATE));
        assertFalse(user.contains(PermissionName.USER_DEACTIVATE));
    }

    @Test
    void candidatesKeepProfileEditing() {
        // USER_UPDATE stays with candidates; it is what they need for their own
        // profile, and it is no longer a route to role assignment.
        assertTrue(RbacSeedConfig.userPermissions().contains(PermissionName.USER_UPDATE));
    }

    @Test
    void adminAndSuperAdminDifferByExactlyTheReservedWrites() {
        EnumSet<PermissionName> everything = EnumSet.allOf(PermissionName.class);
        EnumSet<PermissionName> difference = EnumSet.copyOf(everything);
        difference.removeAll(RbacSeedConfig.adminPermissions());

        assertEquals(
                RbacSeedConfig.SUPER_ADMIN_ONLY,
                difference,
                "the only difference between admin and super admin should be the reserved writes");
    }
}
