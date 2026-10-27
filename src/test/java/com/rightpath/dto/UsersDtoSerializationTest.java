package com.rightpath.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What {@link UsersDto} does and does not put on the wire.
 *
 * <p>This DTO is both a request body and the shape the user list is serialised
 * as, which is how it came to be copying the stored BCrypt hash into every
 * listing response. The asymmetry is the contract — accepted inbound, never
 * emitted outbound — and it is one annotation away from silently regressing, so
 * it is pinned here rather than left to review.</p>
 */
class UsersDtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private UsersDto sample() {
        UsersDto dto = new UsersDto();
        dto.setEmail("someone@example.test");
        dto.setFirstName("Some");
        dto.setLastName("One");
        dto.setMobileNumber("9990000000");
        dto.setPassword("$2a$10$notARealHashButShapedLikeOne");
        dto.setRoles(List.of("ADMIN"));
        return dto;
    }

    @Test
    void passwordIsNeverSerialised() throws Exception {
        String json = mapper.writeValueAsString(sample());

        assertFalse(json.contains("password"), "the password field must not appear in a response");
        assertFalse(json.contains("$2a$10$"), "the stored hash must not appear in a response");
    }

    @Test
    void passwordIsStillAcceptedOnTheWayIn() throws Exception {
        // Registration posts this DTO. Write-only must not become unreadable, or
        // every sign-up would arrive with a null password.
        UsersDto parsed = mapper.readValue(
                "{\"email\":\"a@b.test\",\"firstName\":\"A\",\"lastName\":\"B\","
                        + "\"mobileNumber\":\"1\",\"password\":\"Secret!1\"}",
                UsersDto.class);

        assertEquals("Secret!1", parsed.getPassword());
    }

    @Test
    void rolesAreSerialisedForTheRoleColumn() throws Exception {
        String json = mapper.writeValueAsString(sample());

        assertTrue(json.contains("\"roles\""), "roles must be present for the client role column");
        assertTrue(json.contains("ADMIN"));
    }

    @Test
    void emptyAndAbsentRolesStaySeparateOnTheWire() throws Exception {
        // The client renders "No role" for [] and "Not loaded" for absent, so the
        // two must survive serialisation as different values rather than both
        // collapsing to null.
        UsersDto noRoles = sample();
        noRoles.setRoles(List.of());
        assertTrue(mapper.writeValueAsString(noRoles).contains("\"roles\":[]"));

        UsersDto notLookedUp = sample();
        notLookedUp.setRoles(null);
        assertTrue(mapper.writeValueAsString(notLookedUp).contains("\"roles\":null"));

        assertNotNull(noRoles.getRoles());
    }
}
