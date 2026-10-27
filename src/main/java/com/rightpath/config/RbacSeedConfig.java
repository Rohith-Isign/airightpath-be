package com.rightpath.config;

import java.util.EnumSet;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.rightpath.entity.Permission;
import com.rightpath.entity.Role;
import com.rightpath.rbac.PermissionName;
import com.rightpath.rbac.RoleName;
import com.rightpath.repository.PermissionRepository;
import com.rightpath.repository.RoleRepository;

@Configuration
public class RbacSeedConfig {

    /** Runs first, so {@link SuperAdminInitializer} finds roles and permissions in place. */
    public static final int ORDER = 10;

    /**
     * Writes reserved to SUPER_ADMIN.
     *
     * <p>Activation decides who can sign in at all, and {@code ROLE_MANAGE}
     * decides who holds power — an admin able to grant SUPER_ADMIN, to anyone
     * including themselves, already is one, which would make the distinction
     * between the two roles decorative.</p>
     */
    static final EnumSet<PermissionName> SUPER_ADMIN_ONLY = EnumSet.of(
            PermissionName.USER_ACTIVATE,
            PermissionName.USER_DEACTIVATE,
            PermissionName.ROLE_MANAGE);

    /**
     * What ADMIN is seeded with: everything except {@link #SUPER_ADMIN_ONLY}.
     *
     * <p>Read versus write. {@code USER_LIST} is included — an admin runs hiring
     * day to day and needs to see both candidates and colleagues, and
     * withholding it left User Management reachable from the sidebar and refused
     * by the API, so an admin's only signal was a 403.</p>
     *
     * <p>Exposed as a method rather than inlined in the seeding lambda so the
     * boundary can be asserted without booting the application — see
     * {@code RbacPermissionSplitTest}. It is a security boundary, and one
     * careless {@code allOf} would widen it silently.</p>
     */
    static EnumSet<PermissionName> adminPermissions() {
        EnumSet<PermissionName> perms = EnumSet.allOf(PermissionName.class);
        perms.removeAll(SUPER_ADMIN_ONLY);
        return perms;
    }

    /** What USER (a candidate) is seeded with: their own profile and the hiring flow. */
    static EnumSet<PermissionName> userPermissions() {
        return EnumSet.of(
                PermissionName.USER_READ,
                PermissionName.USER_UPDATE,
                PermissionName.RESUME_UPLOAD,
                PermissionName.RESUME_UPDATE,
                PermissionName.RESUME_VIEW,
                PermissionName.ASSESSMENT_SUBMIT,
                PermissionName.ASSESSMENT_RESULT_SUBMIT,
                PermissionName.JOB_POST_READ,
                PermissionName.JOB_APPLY,
                PermissionName.INTERVIEW_START,
                PermissionName.INTERVIEW_ANSWER,
                PermissionName.COMPILER_RUN,
                PermissionName.COMPILER_RESULTS_READ);
    }

    /**
     * Seeds roles + permissions if they don't exist.
     *
     * With spring.jpa.hibernate.ddl-auto=update, this is the simplest way to get a baseline.
     * Later we can move to Flyway/Liquibase for proper migrations.
     */
    @Bean
    @Order(ORDER)
    CommandLineRunner rbacSeed(PermissionRepository permissions, RoleRepository roles) {
        return args -> {
            // 1) permissions
            for (PermissionName pn : PermissionName.values()) {
                permissions.findByName(pn).orElseGet(() -> {
                    Permission p = new Permission();
                    p.setName(pn);
                    return permissions.save(p);
                });
            }

            // Helper: fetch Permission entities
            java.util.function.Function<PermissionName, Permission> get = pn -> permissions.findByName(pn).orElseThrow();

            // 2) roles
            // SUPER_ADMIN used to be updated only if it already existed, because some
            // deployments had `roles.name` sized too small to hold the value and
            // startup failed on the insert. Role.name now pins the column at
            // varchar(50), so the value always fits and the role is created like any
            // other — a database where it is missing has no way to grant a first
            // administrator, which is the gap SuperAdminInitializer then closes.
            Role superAdmin = roles.findByName(RoleName.SUPER_ADMIN).orElseGet(() -> {
                Role r = new Role();
                r.setName(RoleName.SUPER_ADMIN);
                return roles.save(r);
            });
            Role admin = roles.findByName(RoleName.ADMIN).orElseGet(() -> {
                Role r = new Role();
                r.setName(RoleName.ADMIN);
                return roles.save(r);
            });
            Role user = roles.findByName(RoleName.USER).orElseGet(() -> {
                Role r = new Role();
                r.setName(RoleName.USER);
                return roles.save(r);
            });

            // 3) role -> perms defaults
            // Super Admin gets everything
            superAdmin.getPermissions().clear();
            for (PermissionName pn : PermissionName.values()) {
                superAdmin.getPermissions().add(get.apply(pn));
            }
            roles.save(superAdmin);

            EnumSet<PermissionName> adminPerms = adminPermissions();
            admin.getPermissions().clear();
            for (PermissionName pn : adminPerms) {
                admin.getPermissions().add(get.apply(pn));
            }
            roles.save(admin);

            EnumSet<PermissionName> userPerms = userPermissions();
            user.getPermissions().clear();
            for (PermissionName pn : userPerms) {
                user.getPermissions().add(get.apply(pn));
            }
            roles.save(user);
        };
    }
}
