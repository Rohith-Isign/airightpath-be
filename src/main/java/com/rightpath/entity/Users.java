package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.dto.UsersDto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity class representing the "Users" table in the database. Stores
 * user-related information such as email, name, password, roles/authorities,
 * and contact details.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
public class Users {

	/**
	 * Unique identifier for the user (email address). Must follow the specified
	 * email format and constraints.
	 */
	@Id
	@Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Email must be a valid email address")
	@Size(min = 6, max = 254, message = "Email must be between 6 and 254 characters")
	@NotBlank(message = "Email is mandatory")
	private String email;

	/**
	 * First name of the user. Must be between 1 and 25 characters and cannot be
	 * blank.
	 */
	@NotBlank(message = "First name is mandatory")
	@Size(min = 1, max = 25, message = "First name must be between 1 and 25 characters")
	private String firstName;

	/**
	 * Last name of the user. Must be between 1 and 25 characters and cannot be
	 * blank.
	 */
	@NotBlank(message = "Last name is mandatory")
	@Size(min = 1, max = 25, message = "Last name must be between 1 and 25 characters")
	private String lastName;

	/**
	 * Password for the user. Must contain at least one special character and cannot
	 * be blank.
	 */
	@NotBlank(message = "Password is mandatory")
	@Pattern(regexp = "^(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>?/]).*$",

			message = "Password must contain at least one special character")
	private String password;

	/**
	 * Indicates whether the user account is enabled.
	 */
	private Boolean enabled = true;

	/**
	 * Legacy note: authorities are no longer persisted on the user record.
	 * Spring Security authorities are derived from RBAC tables (user_roles + role_permissions).
	 */

	/**
	 * Primary mobile number of the user. Must be unique and cannot be null.
	 */
	@Column(nullable = false, unique = true)
	private String mobileNumber;

	/**
	 * Alternative mobile number for the user.
	 */
	private String alternativeMobileNumber;

	/**
	 * Profile image of the user. Stored as a large binary object (LONGBLOB) in the
	 * database.
	 */
	@Lob
	@Column(name = "profileImage", columnDefinition = "LONGBLOB")
	private byte[] profileImage;

	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		this.createdAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();
	}

	@PreUpdate
	protected void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}

	/**
	 * Constructor to initialize a Users entity from a UsersDto object.
	 * 
	 * @param users UsersDto object containing user details.
	 */
	public Users(UsersDto users) {
		this.email = users.getEmail();
		this.firstName = users.getFirstName();
		this.lastName = users.getLastName();
		this.password = users.getPassword();
		this.enabled = users.getEnabled();
		this.mobileNumber = users.getMobileNumber();
		this.alternativeMobileNumber = users.getAlternativeMobileNumber();
		this.profileImage = users.getProfileImage();
	}

	/**
	 * Parameterized constructor for creating a Users entity.
	 * 
	 * @param email        User's email.
	 * @param firstName    User's first name.
	 * @param lastName     User's last name.
	 * @param password     User's password.
	 * @param enabled      Account enabled status.
	 * @param authorities  Set of user roles/authorities.
	 * @param mobileNumber Primary mobile number.
	 */
	public Users(String email, String firstName, String lastName, String password, Boolean enabled, String mobileNumber) {
		this.email = email;
		this.firstName = firstName;
		this.lastName = lastName;
		this.password = password;
		this.enabled = enabled;
		this.mobileNumber = mobileNumber;
	}

}
