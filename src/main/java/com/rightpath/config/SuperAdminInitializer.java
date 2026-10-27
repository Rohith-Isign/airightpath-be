package com.rightpath.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.Permission;
import com.rightpath.entity.Role;
import com.rightpath.entity.UserRole;
import com.rightpath.entity.Users;
import com.rightpath.rbac.PermissionName;
import com.rightpath.rbac.RoleName;
import com.rightpath.repository.PermissionRepository;
import com.rightpath.repository.RoleRepository;
import com.rightpath.repository.UserRoleRepository;
import com.rightpath.repository.UsersRepository;

/**
 * Creates the SUPER_ADMIN role and one account holding it, when nobody holds it
 * yet.
 *
 * <p>Every administrative endpoint is behind a permission, and permissions come
 * from a role somebody has been granted. On an empty database nobody has been
 * granted anything, so there is no request that can create the first admin —
 * the only way in is to make it before the application starts serving. That is
 * all this does.</p>
 *
 * <p>It is idempotent and safe on an established database: once any active
 * SUPER_ADMIN grant exists it does nothing at all. In particular it never
 * rewrites the password of an account that is already there, so an admin who
 * has changed their password does not have it reset back to the yml value on
 * the next restart.</p>
 *
 * <p>Runs after {@link RbacSeedConfig}, which owns the role-to-permission
 * mapping. It can still create the role itself if that seed has not run, so the
 * account is never left holding a role with no permissions.</p>
 */
@Component
@Order(SuperAdminInitializer.ORDER)
public class SuperAdminInitializer implements CommandLineRunner {

    /** After {@link RbacSeedConfig#ORDER}, so roles and permissions already exist. */
    public static final int ORDER = RbacSeedConfig.ORDER + 10;

    private static final Logger logger = LoggerFactory.getLogger(SuperAdminInitializer.class);

    /** Matches the bounds {@code PasswordValidator} applies to registrations. */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 20;

    private final SuperAdminProperties properties;
    private final UsersRepository usersRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminInitializer(
            SuperAdminProperties properties,
            UsersRepository usersRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PermissionRepository permissionRepository,
            PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.usersRepository = usersRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Nothing here throws. A misconfigured bootstrap credential is a reason to
     * refuse to create the account and say so loudly, not a reason to stop an
     * application that is otherwise serving candidates.
     */
    @Override
    @Transactional
    public void run(String... args) {
        if (!properties.isEnabled()) {
            logger.info("Super admin bootstrap disabled (rightpath.security.super-admin.enabled=false).");
            return;
        }

        String email = trimToNull(properties.getEmail());
        String password = properties.getPassword();

        if (email == null || isBlank(password)) {
            logger.warn("Super admin bootstrap skipped: set rightpath.security.super-admin.email and .password "
                    + "(env SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD) to create the first administrator.");
            return;
        }

        Role superAdmin = ensureSuperAdminRole();

        // The real guard. Anyone holding the role can grant it to others through
        // the API, so once one grant exists the bootstrap has done its job and
        // must stay out of the way — including when the configured email differs
        // from whoever the admin ended up being.
        if (userRoleRepository.existsByRole_NameAndActiveTrue(RoleName.SUPER_ADMIN)) {
            logger.debug("Super admin bootstrap skipped: an active SUPER_ADMIN grant already exists.");
            return;
        }

        Users account = usersRepository.findByEmail(email).orElse(null);
        if (account == null) {
            account = createAccount(email, password);
            if (account == null) {
                return;
            }
        } else {
            // Promoting rather than recreating: email is the primary key, so there
            // is no second row to make, and the existing password is theirs.
            logger.info("Super admin bootstrap: {} already exists — granting SUPER_ADMIN, password left unchanged.",
                    email);
        }

        grantSuperAdmin(account, superAdmin);
        logger.info("Super admin bootstrap complete: {} now holds SUPER_ADMIN.", email);
    }

    /**
     * The role, with every permission, creating it if {@link RbacSeedConfig} has
     * not. Granting a role with an empty permission set would produce an account
     * that logs in and can do nothing, which reads as a broken password.
     */
    private Role ensureSuperAdminRole() {
        Role existing = roleRepository.findByName(RoleName.SUPER_ADMIN).orElse(null);
        if (existing != null) {
            return existing;
        }

        logger.info("SUPER_ADMIN role not found — creating it with all {} permissions.",
                PermissionName.values().length);
        Role role = new Role();
        role.setName(RoleName.SUPER_ADMIN);
        role = roleRepository.save(role);

        for (PermissionName name : PermissionName.values()) {
            Permission permission = permissionRepository.findByName(name).orElseGet(() -> {
                Permission created = new Permission();
                created.setName(name);
                return permissionRepository.save(created);
            });
            role.getPermissions().add(permission);
        }
        return roleRepository.save(role);
    }

    /** @return the saved account, or {@code null} when the configuration rules it out. */
    private Users createAccount(String email, String password) {
        int length = password.length();
        if (length < MIN_PASSWORD_LENGTH || length > MAX_PASSWORD_LENGTH) {
            logger.error("Super admin bootstrap skipped: configured password is {} characters, "
                    + "outside the {}-{} the login policy allows.", length, MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH);
            return null;
        }

        String mobileNumber = trimToNull(properties.getMobileNumber());
        if (mobileNumber == null) {
            logger.error("Super admin bootstrap skipped: mobile-number is blank and users.mobile_number is NOT NULL.");
            return null;
        }
        if (usersRepository.existsByMobileNumber(mobileNumber)) {
            logger.error("Super admin bootstrap skipped: mobile number {} already belongs to another account. "
                    + "Set rightpath.security.super-admin.mobile-number to a free one.", mobileNumber);
            return null;
        }

        Users account = new Users(
                email,
                orDefault(properties.getFirstName(), "Super"),
                orDefault(properties.getLastName(), "Admin"),
                passwordEncoder.encode(password),
                true,
                mobileNumber);

        logger.info("Super admin bootstrap: creating {}.", email);
        return usersRepository.save(account);
    }

    private void grantSuperAdmin(Users account, Role superAdmin) {
        if (userRoleRepository.findByUser_EmailAndRole_NameAndActiveTrue(account.getEmail(), RoleName.SUPER_ADMIN)
                .isPresent()) {
            return;
        }
        UserRole grant = new UserRole();
        grant.setUser(account);
        grant.setRole(superAdmin);
        grant.setActive(true);
        userRoleRepository.save(grant);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
