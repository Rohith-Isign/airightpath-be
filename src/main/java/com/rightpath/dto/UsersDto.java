package com.rightpath.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rightpath.entity.Users;

import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UsersDto {
	@Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Email must be a valid email address")
	@Size(min = 6, max = 254, message = "Email must be between 6 and 254 characters")
	@NotBlank(message = "Email is mandatory")
	private String email;
	@NotBlank(message = "First name is mandatory")
	@Size(min = 1, max = 25, message = "First name must be between 1 and 25 characters")
	private String firstName;
	@NotBlank(message = "Last name is mandatory")
	@Size(min = 1, max = 25, message = "Last name must be between 1 and 25 characters")
	private String lastName;
	/**
	 * Write-only: registration sends one, and no response ever returns one.
	 *
	 * <p>This DTO is both a request body and the shape the user list is serialised
	 * as, and its entity constructor copies the stored BCrypt hash across — so
	 * listing users handed every hash to the caller. A hash is not display data
	 * and nothing client-side reads this field. {@code WRITE_ONLY} rather than
	 * {@code @JsonIgnore} because the field still has to be accepted on the way
	 * in, or registration breaks.</p>
	 */
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@NotBlank(message = "Password is mandatory")
	@Pattern(regexp = "^(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>?/]).*$",

			message = "Password must contain at least one special character")
	private String password;
	private Boolean enabled = true;
	@Column(nullable = false, unique = true)
	private String mobileNumber;

	private String alternativeMobileNumber;

	@Lob
	@Column(name = "profileImage", columnDefinition = "LONGBLOB")
	private byte[] profileImage;

	/**
	 * Active role names, e.g. {@code ["ADMIN"]}. Filled in by the listing
	 * service, which reads every user's roles in one query; left null by the
	 * entity constructor below, since a {@code Users} row does not carry them.
	 *
	 * <p>Null and empty mean different things and are both real: null is "not
	 * looked up on this path", empty is "looked up, and this account holds no
	 * role". A client showing a role column has to be able to tell them apart.</p>
	 */
	private List<String> roles;

	/**
	 * Projection constructor for list endpoints — the columns a roster shows.
	 *
	 * <p>Deliberately narrower than {@link #UsersDto(Users)}. Selecting entities
	 * made every page read two columns nobody displays: the BCrypt hash, and
	 * {@code profileImage}, a LONGBLOB that avatars are fetched from separately
	 * one at a time. On a 25-row page that is 25 blobs off disk to render a table
	 * of names.</p>
	 *
	 * <p>{@code roles} is filled in afterwards by a single batch query, so it is
	 * not a parameter here.</p>
	 */
	public UsersDto(String email, String firstName, String lastName, Boolean enabled,
			String mobileNumber, String alternativeMobileNumber) {
		this.email = email;
		this.firstName = firstName;
		this.lastName = lastName;
		this.enabled = enabled;
		this.mobileNumber = mobileNumber;
		this.alternativeMobileNumber = alternativeMobileNumber;
	}

	public UsersDto(Users users) {
		this.email = users.getEmail();
		this.firstName = users.getFirstName();
		this.lastName = users.getLastName();
		this.password = users.getPassword();
		this.enabled = users.getEnabled();
		this.mobileNumber = users.getMobileNumber();
		this.alternativeMobileNumber = users.getAlternativeMobileNumber();
		this.profileImage = users.getProfileImage();
	}
}
