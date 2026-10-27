package com.rightpath.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 * The bootstrap exists for exactly one situation — a database nobody can log
 * into as an administrator — and it has to stay out of the way in every other.
 *
 * <p>Getting that wrong is expensive in both directions: skip when it should
 * act and a fresh deployment is unusable with no request that can fix it; act
 * when it should skip and a live admin finds their password reset to a value
 * committed in a yml file.</p>
 */
class SuperAdminInitializerTest {

    private static final String EMAIL = "superadmin@example.com";
    private static final String PASSWORD = "Admin@12345";
    private static final String MOBILE = "9000000001";

    private SuperAdminProperties properties;
    private UsersRepository usersRepository;
    private RoleRepository roleRepository;
    private UserRoleRepository userRoleRepository;
    private PermissionRepository permissionRepository;
    private PasswordEncoder passwordEncoder;
    private SuperAdminInitializer initializer;

    @BeforeEach
    void setUp() {
        properties = new SuperAdminProperties();
        properties.setEmail(EMAIL);
        properties.setPassword(PASSWORD);
        properties.setMobileNumber(MOBILE);

        usersRepository = mock(UsersRepository.class);
        roleRepository = mock(RoleRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        permissionRepository = mock(PermissionRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();

        // Empty database by default: no role, no user, no grant.
        when(roleRepository.findByName(RoleName.SUPER_ADMIN)).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));
        when(permissionRepository.findByName(any(PermissionName.class))).thenReturn(Optional.empty());
        when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRoleRepository.existsByRole_NameAndActiveTrue(RoleName.SUPER_ADMIN)).thenReturn(false);
        when(userRoleRepository.findByUser_EmailAndRole_NameAndActiveTrue(EMAIL, RoleName.SUPER_ADMIN))
                .thenReturn(Optional.empty());
        when(usersRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(usersRepository.existsByMobileNumber(MOBILE)).thenReturn(false);
        when(usersRepository.save(any(Users.class))).thenAnswer(inv -> inv.getArgument(0));

        initializer = new SuperAdminInitializer(properties, usersRepository, roleRepository,
                userRoleRepository, permissionRepository, passwordEncoder);
    }

    @Test
    void createsRoleAndAccountOnAnEmptyDatabase() {
        initializer.run();

        ArgumentCaptor<Role> role = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, org.mockito.Mockito.atLeastOnce()).save(role.capture());
        assertEquals(RoleName.SUPER_ADMIN, role.getValue().getName());

        ArgumentCaptor<Users> user = ArgumentCaptor.forClass(Users.class);
        verify(usersRepository).save(user.capture());
        assertEquals(EMAIL, user.getValue().getEmail());
        assertEquals(MOBILE, user.getValue().getMobileNumber());
        assertTrue(user.getValue().getEnabled());

        verify(userRoleRepository).save(any(UserRole.class));
    }

    /** A role with no permissions logs in and can do nothing, which reads as a broken account. */
    @Test
    void newRoleCarriesEveryPermission() {
        initializer.run();

        ArgumentCaptor<Role> role = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, org.mockito.Mockito.atLeastOnce()).save(role.capture());
        assertEquals(PermissionName.values().length, role.getValue().getPermissions().size());
    }

    @Test
    void storesThePasswordHashedNotAsConfigured() {
        initializer.run();

        ArgumentCaptor<Users> user = ArgumentCaptor.forClass(Users.class);
        verify(usersRepository).save(user.capture());
        String stored = user.getValue().getPassword();
        assertNotEquals(PASSWORD, stored);
        assertTrue(passwordEncoder.matches(PASSWORD, stored), "configured password must verify against the hash");
    }

    /**
     * Once someone holds the role they can grant it through the API, so the
     * bootstrap's job is done — including when the configured email is not the
     * account that ended up holding it.
     */
    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        when(userRoleRepository.existsByRole_NameAndActiveTrue(RoleName.SUPER_ADMIN)).thenReturn(true);

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    /** Email is the primary key — there is no second row to create, only a role to grant. */
    @Test
    void promotesAnExistingAccountWithoutTouchingItsPassword() {
        Users existing = new Users(EMAIL, "Real", "Person", "$2a$10$theirOwnHash", true, "9111111111");
        when(usersRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
        verify(userRoleRepository).save(any(UserRole.class));
        assertEquals("$2a$10$theirOwnHash", existing.getPassword());
    }

    @Test
    void skipsWhenNoPasswordIsConfigured() {
        properties.setPassword("  ");

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
        verify(roleRepository, never()).save(any(Role.class));
    }

    @Test
    void skipsWhenNoEmailIsConfigured() {
        properties.setEmail(null);

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
    }

    @Test
    void skipsWhenDisabled() {
        properties.setEnabled(false);

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
        verify(roleRepository, never()).save(any(Role.class));
    }

    /**
     * A password the login policy would reject creates an account nobody can use
     * and no error anyone sees until they try to log in.
     */
    @Test
    void refusesAPasswordOutsideTheLoginPolicy() {
        properties.setPassword("short");

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
    }

    /** mobile_number is NOT NULL and unique, so a clash would fail the insert. */
    @Test
    void skipsWhenTheMobileNumberBelongsToSomeoneElse() {
        when(usersRepository.existsByMobileNumber(MOBILE)).thenReturn(true);

        initializer.run();

        verify(usersRepository, never()).save(any(Users.class));
        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    /** {@link RbacSeedConfig} normally makes the role; when it has, reuse it as-is. */
    @Test
    void reusesTheSeededRoleWhenItAlreadyExists() {
        Role seeded = new Role();
        seeded.setName(RoleName.SUPER_ADMIN);
        when(roleRepository.findByName(RoleName.SUPER_ADMIN)).thenReturn(Optional.of(seeded));

        initializer.run();

        verify(roleRepository, never()).save(any(Role.class));
        verify(usersRepository).save(any(Users.class));
    }
}
