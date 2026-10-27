package com.rightpath.dto;

import com.rightpath.rbac.RoleName;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new staff account: the person, plus the role they are being given.
 *
 * <p>Its own type rather than reusing {@link UsersDto}, for two reasons. That
 * DTO has no role field, so the role would have to travel as a loose query
 * parameter on a request whose whole point is the pairing. And it carries
 * fields — {@code enabled}, {@code profileImage} — that a caller must not get
 * to set when minting an administrator.</p>
 *
 * <p>{@code role} is constrained by the enum, so "ADMIN" and "SUPER_ADMIN" are
 * the only staff values a request can name and an unknown string is rejected by
 * deserialisation rather than reaching the service.</p>
 *
 * @param firstName    given name, as it appears in the admin list
 * @param lastName     family name
 * @param email        login identity; must not already exist
 * @param mobileNumber contact number; unique across all accounts
 * @param password     initial password, encoded before it is stored
 * @param role         the role to grant
 */
public record CreateStaffUserRequest(
        @NotBlank(message = "First name is mandatory")
        @Size(min = 1, max = 25, message = "First name must be between 1 and 25 characters")
        String firstName,

        @NotBlank(message = "Last name is mandatory")
        @Size(min = 1, max = 25, message = "Last name must be between 1 and 25 characters")
        String lastName,

        @NotBlank(message = "Email is mandatory")
        @Email(message = "Email must be a valid email address")
        @Size(min = 6, max = 254, message = "Email must be between 6 and 254 characters")
        String email,

        @NotBlank(message = "Mobile number is mandatory")
        @Pattern(regexp = "^[0-9+\\-\\s()]{6,20}$", message = "Mobile number is not a valid number")
        String mobileNumber,

        /*
         * Same special-character rule registration applies, so an account made
         * here is not held to a weaker standard than one someone signs up with.
         * Length is checked in the service by PasswordValidator, which owns the
         * configured minimum and maximum.
         */
        @NotBlank(message = "Password is mandatory")
        @Pattern(
                regexp = "^(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>?/]).*$",
                message = "Password must contain at least one special character")
        String password,

        @NotNull(message = "Role is mandatory")
        RoleName role
) {

    /** Whether this request asks for the most privileged role in the system. */
    public boolean grantsSuperAdmin() {
        return role == RoleName.SUPER_ADMIN;
    }

    /** Maps onto the shape the user service creates accounts from. */
    public UsersDto toUsersDto() {
        UsersDto dto = new UsersDto();
        dto.setFirstName(firstName);
        dto.setLastName(lastName);
        dto.setEmail(email);
        dto.setMobileNumber(mobileNumber);
        dto.setPassword(password);
        dto.setEnabled(Boolean.TRUE);
        return dto;
    }
}
