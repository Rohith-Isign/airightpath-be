package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.dto.UsersDto;
import com.rightpath.rbac.RoleName;
import com.rightpath.service.impl.UserDetailsServiceImpl;

/** Checks the service mapping and JSON response against the configured database, without writes. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never"})
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StaffUserResponseTest {
    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void staffResponseIncludesActiveRolesAndNeverPasswords() throws Exception {
        UserDetailsServiceImpl service = new UserDetailsServiceImpl();
        ReflectionTestUtils.setField(service, "userRepository", usersRepository);
        ReflectionTestUtils.setField(service, "userRoleRepository", userRoleRepository);

        List<UsersDto> response = service.getStaffUsers();
        // Paginated now: the role queries were rewritten to EXISTS and take a
        // Pageable, because the previous NOT IN + DISTINCT + ORDER BY form failed
        // with MySQL 1038. A page large enough to hold every staff account keeps
        // this comparing the same set it always did.
        Set<String> expected = usersRepository.findPageByActiveRoleIn(
                List.of(RoleName.ADMIN, RoleName.SUPER_ADMIN),
                PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "email")))
                .getContent().stream()
                .map(UsersDto::getEmail).collect(Collectors.toSet());
        assertEquals(expected, response.stream().map(UsersDto::getEmail).collect(Collectors.toSet()));
        assertEquals(expected.size(), response.size());

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertTrue(json.isArray());
        for (var user : json) {
            assertFalse(user.has("password"));
            assertTrue(user.get("roles").isArray());
            Set<String> activeRoles = userRoleRepository.findActiveRolesByEmails(
                    List.of(user.get("email").asText())).stream()
                    .map(pair -> pair.role().name()).collect(Collectors.toSet());
            Set<String> returnedRoles = new java.util.HashSet<>();
            user.get("roles").forEach(role -> returnedRoles.add(role.asText()));
            assertEquals(activeRoles, returnedRoles);
            assertTrue(returnedRoles.contains("ADMIN") || returnedRoles.contains("SUPER_ADMIN"));
        }
    }
}
