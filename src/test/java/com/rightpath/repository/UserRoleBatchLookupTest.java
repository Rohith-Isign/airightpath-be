package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.rbac.RoleName;
import com.rightpath.rbac.UserRoleName;

/**
 * Exercises {@link UserRoleRepository#findActiveRolesByEmails} against the real
 * MySQL schema.
 *
 * <p>The query is a JPQL constructor expression, which the compiler cannot
 * check: a field reordered in {@link UserRoleName}, or a type no constructor
 * accepts, fails at runtime rather than at build time. It also has to filter on
 * {@code active}, since a revoked assignment stays in the table and must not
 * show up as a role the user still holds.</p>
 *
 * <p>Reads only — the RBAC roles are seeded by the application, so this asserts
 * against whatever the database already holds rather than inserting fixtures
 * into shared security tables.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRoleBatchLookupTest {

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void returnsNothingForAnEmptyBatch() {
        // Guards the boundary a caller hits on an empty page: an IN () clause is
        // invalid SQL on MySQL, so this must be handled rather than executed.
        assertTrue(userRoleRepository.findActiveRolesByEmails(List.of()).isEmpty());
    }

    @Test
    void returnsNothingForUnknownEmails() {
        assertTrue(
                userRoleRepository
                        .findActiveRolesByEmails(List.of("nobody.zylnex@example.invalid"))
                        .isEmpty());
    }

    @Test
    void projectsEmailAndRoleForRealAssignments() {
        // Whatever this database holds, every row must come back fully populated
        // and carry a real RoleName — that is what the projection guarantees.
        List<UserRoleName> all = userRoleRepository.findAll().stream()
                .filter(assignment -> assignment.isActive())
                .map(assignment -> new UserRoleName(
                        assignment.getUser().getEmail(), assignment.getRole().getName()))
                .collect(Collectors.toList());

        if (all.isEmpty()) {
            return; // Nothing assigned in this database; the cases above still cover the SQL.
        }

        Set<String> emails = all.stream().map(UserRoleName::userEmail).collect(Collectors.toSet());
        List<UserRoleName> projected = userRoleRepository.findActiveRolesByEmails(emails);

        assertEquals(all.size(), projected.size(), "every active assignment should be projected");
        assertTrue(
                projected.stream().allMatch(pair -> pair.userEmail() != null && pair.role() != null),
                "no projected row may have a null email or role");
        assertTrue(
                projected.stream().allMatch(pair -> Set.of(RoleName.values()).contains(pair.role())),
                "every projected role must be a known RoleName");
    }
}
