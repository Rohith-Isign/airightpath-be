// Package declaration
package com.rightpath.service.impl;

// Import necessary Java and Spring packages
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory; // For logging
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy; // Lazy loads beans
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AuthenticationManager; // Used for authenticating users
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // Used for passing authentication data
import org.springframework.security.core.Authentication; // Represents authentication token
import org.springframework.security.core.GrantedAuthority; // Represents roles/authorities granted to the user
import org.springframework.security.core.authority.SimpleGrantedAuthority; // Simple implementation of GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails; // Core interface for user information
import org.springframework.security.core.userdetails.UserDetailsService; // Loads user-specific data
import org.springframework.security.core.userdetails.UsernameNotFoundException; // Thrown if user is not found
import org.springframework.security.crypto.password.PasswordEncoder; // Interface for encoding passwords
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile; // Represents uploaded file

// Import custom project classes
import com.rightpath.dto.Login;
import com.rightpath.dto.UsersDto;
import com.rightpath.entity.Users;
import com.rightpath.exceptions.InactiveUserException;
import com.rightpath.exceptions.UserAlreadyInDatabaseException;
import com.rightpath.exceptions.StorageException;
import com.rightpath.exceptions.UserNotFoundDbException;
import com.rightpath.repository.RoleRepository;
import com.rightpath.repository.UserRoleRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.rbac.UserRoleName;
import com.rightpath.service.EmailService;
import com.rightpath.service.rbac.RbacAuthorityService;
import com.rightpath.util.ThreadLocalUserContext;
import com.rightpath.validator.PasswordValidator;

// Mark the class as a service layer bean
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

	// Logger for this class
	private static final Logger logger = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

	// Inject PasswordValidator to check password strength/size
	@Autowired
	private PasswordValidator passwordValidator;

	// Inject repository for DB operations on Users
	@Autowired
	private UsersRepository userRepository;

	// Inject password encoder for hashing passwords
	@Autowired
	private PasswordEncoder bCryptPasswordEncoder;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private UserRoleRepository userRoleRepository;

	// RBAC authority resolver (roles + permissions)
	@Autowired
	private RbacAuthorityService rbacAuthorityService;

	// AuthenticationManager is lazily injected to avoid circular dependency
	@Lazy
	@Autowired
	private AuthenticationManager authenticationManager;

	// Service to generate JWT tokens
	@Autowired
	private JwtService jwtService;

	// Email service for sending notifications
	@Autowired
	private EmailService emailService;

	// Method to load a user by their email address (used by Spring Security)
	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		logger.info("Loading user by email: {}", email);

		// Fetch user by email or throw custom exception if not found
		Users user = userRepository.findByEmail(email).orElseThrow(() -> {
			logger.error("User not found in database for email: {}", email);
			return new UserNotFoundDbException("User does not exist");
		});

		// Ensure the user account is active
		checkActiveUser(user.getEnabled());

		// Set user in thread-local context for downstream usage
		ThreadLocalUserContext.setUser(user);

		// Convert authorities into Spring Security format.
		// Prefer RBAC-derived authorities (ROLE_* + permissions) if present.
		Set<String> authorityStrings = rbacAuthorityService.resolveAuthorities(user.getEmail());
		// If user has no RBAC roles yet, treat as having no authorities.
		// (We assign USER by default at registration; this is just defensive.)
		if (authorityStrings == null) {
			authorityStrings = java.util.Collections.emptySet();
		}
		Set<GrantedAuthority> grantedAuthorities = authorityStrings.stream()
				.filter(a -> a != null && !a.isBlank())
				.map(a -> new SimpleGrantedAuthority(a.startsWith("ROLE_") ? a.toUpperCase() : a))
				.collect(Collectors.toSet());

		logger.debug("User loaded successfully: {}", email);

		// Return Spring Security User object
		return new org.springframework.security.core.userdetails.User(user.getEmail(), user.getPassword(),
				grantedAuthorities);
	}

	/**
	 * Registers a new user.
	 *
	 * <p>Transactional because the account and its default role have to land
	 * together or not at all. Without it, {@code save} committed on its own and
	 * anything that failed afterwards left the row behind: the caller saw the
	 * registration fail, and every retry from then on answered 409 "Email already
	 * exists" for an account that had never finished being created and could not
	 * be logged into. Email is the primary key, so there is no second row to
	 * create and no way for the candidate to clear it themselves.</p>
	 */
	@Transactional
	public Map<String, String> addUser(UsersDto usersDto) {
	    logger.info("Attempting to add new user: {}", usersDto.getEmail());

	    // ✅ 1. Check if email already exists
	    if (userRepository.existsByEmail(usersDto.getEmail())) {
	        throw new UserAlreadyInDatabaseException("Email already exists");
	    }

	    // ✅ 2. Check if mobile number already exists
	    if (userRepository.existsByMobileNumber(usersDto.getMobileNumber())) {
	        throw new UserAlreadyInDatabaseException("Mobile number already exists");
	    }

	    // ✅ 3. Validate password strength
	    passwordValidator.passwordSizeChecker(usersDto.getPassword());

	    // ✅ 4. Encode the password
	    String encodedPassword = bCryptPasswordEncoder.encode(usersDto.getPassword());

	    // ✅ 5. Create and save user (RBAC roles are stored in user_roles)
	    Users user = new Users(
	        usersDto.getEmail(),
	        usersDto.getFirstName(),
	        usersDto.getLastName(),
	        encodedPassword,
	        usersDto.getEnabled(),
	        usersDto.getMobileNumber()
	    );

	    userRepository.save(user);

	    // ✅ 6. Assign default role USER (active)
	    // Be resilient in fresh DBs/environments: if the USER role isn't seeded yet, create it.
	    try {
	        var role = roleRepository.findByName(com.rightpath.rbac.RoleName.USER)
	                .orElseGet(() -> {
	                    logger.warn("Default role USER not found; creating it on the fly");
	                    var r = new com.rightpath.entity.Role();
	                    r.setName(com.rightpath.rbac.RoleName.USER);
	                    return roleRepository.save(r);
	                });

	        // Avoid duplicate links if the method is ever retried.
	        var existing = userRoleRepository
	                .findByUser_EmailAndRole_NameAndActiveTrue(user.getEmail(), com.rightpath.rbac.RoleName.USER)
	                .orElse(null);
	        if (existing == null) {
	            var userRole = new com.rightpath.entity.UserRole();
	            userRole.setUser(user);
	            userRole.setRole(role);
	            userRole.setActive(true);
	            userRoleRepository.save(userRole);
	        }
	    } catch (Exception e) {
	        logger.error("Failed to assign default USER role for {}", usersDto.getEmail(), e);
	        throw e;
	    }

	    // ✅ 7. Send the welcome email — best effort.
	    // The account exists at this point. A greylisted or timed-out SMTP send used
	    // to throw from here, which failed the registration the user had already
	    // completed; unlike an exam link, a welcome mail is a courtesy and nothing
	    // downstream depends on it having arrived.
	    try {
	        emailService.sendSuccessRegistrationEmail(user.getEmail(), user.getLastName(), user.getFirstName(),
	                user.getMobileNumber());
	    } catch (Exception e) {
	        logger.warn("Welcome email failed for {}; the account was still created: {}",
	                user.getEmail(), e.getMessage());
	    }

	    // ✅ 8. Return success
	    return Map.of("message", "success");
	}

	// Update user details and profile image
	public Map<String, String> updateUser(String email, String firstName, String lastName, String mobileNumber,
			String alternativeMobileNumber, MultipartFile profileImage) {
		logger.info("Updating user: {}", email);

		// Fetch the user from DB
		Users user = userRepository.findByEmail(email)
				.orElseThrow(() -> {
					logger.error("User not found for update: {}", email);
					return new UserNotFoundDbException("User not found with email: " + email);
				});

		// Update fields if new values are provided
		if (firstName != null && !firstName.isEmpty())
			user.setFirstName(firstName);
		if (lastName != null && !lastName.isEmpty())
			user.setLastName(lastName);
		if (mobileNumber != null && !mobileNumber.isEmpty())
			user.setMobileNumber(mobileNumber);
		if (alternativeMobileNumber != null && !alternativeMobileNumber.isEmpty())
			user.setAlternativeMobileNumber(alternativeMobileNumber);

		// Update profile image if provided
		if (profileImage != null && !profileImage.isEmpty()) {
			try {
				user.setProfileImage(profileImage.getBytes());
			} catch (IOException e) {
				logger.error("Failed to process profile image for user: {}", email, e);
				throw new StorageException("Error processing profile image");
			}
		}

		// Save updated user to DB
		userRepository.save(user);
		logger.info("User updated successfully: {}", email);

		// Prepare and return response
		Map<String, String> response = new HashMap<>();
		response.put("message", "User updated successfully");

		if (user.getProfileImage() != null) {
			String base64Image = Base64.getEncoder().encodeToString(user.getProfileImage());
			response.put("profileImage", base64Image);
		}

		response.put("firstName", user.getFirstName());
		response.put("lastName", user.getLastName());
		response.put("mobileNumber", user.getMobileNumber());
		response.put("alternativeMobileNumber", user.getAlternativeMobileNumber());

		return response;
	}

	// Authenticate a user and return JWT token + user info
	public Map<String, String> getLoginDetails(Login loginUser) {
		logger.info("Authenticating user: {}", loginUser.getEmail());

		// Validate password length
		passwordValidator.passwordSizeChecker(loginUser.getPassword());

		// Authenticate using Spring Security AuthenticationManager
		Authentication authenticate = authenticationManager
				.authenticate(new UsernamePasswordAuthenticationToken(loginUser.getEmail(), loginUser.getPassword()));

		if (!authenticate.isAuthenticated()) {
			logger.error("Authentication failed for: {}", loginUser.getEmail());
			throw new UsernameNotFoundException("User Name Not Found");
		}

		// Generate JWT token
		UserDetails userDetails = (UserDetails) authenticate.getPrincipal();
		String token = jwtService.generateToken(userDetails);

		// Add token to user map
		Map<String, String> userMap = ThreadLocalUserContext.getUser();
		userMap.put("jwtToken", token);

		logger.info("Authentication successful for: {}", loginUser.getEmail());

		return userMap;
	}

	// Validate if user already exists by email
	public void validateUser(String email) {
		if (userRepository.findByEmail(email).isPresent()) {
			logger.warn("Attempted to register an already existing user: {}", email);
			throw new UserAlreadyInDatabaseException("UserAlready in Database ");
		}
	}

	// Check if user is active
	public void checkActiveUser(Boolean activeUser) {
		if (!activeUser) {
			logger.warn("Inactive user tried to login");
			throw new InactiveUserException("User is Inactive");
		}
	}

	// Mark user as active
	public Map<String, String> updateActive(String email) {
		try {
			logger.info("Activating user: {}", email);
			userRepository.activeByEmail(email);
			Map<String, String> map = new HashMap<>();
			map.put("success", "success");
			return map;
		} catch (UserNotFoundDbException e) {
			logger.error("Failed to activate user: {}", email);
			throw new UserNotFoundDbException("User Not Found");
		}
	}

	// Deactivate or delete user
	public Map<String, String> updateDeactive(String email) {
		try {
			logger.info("Deactivating user: {}", email);
			userRepository.deleteByEmail(email);
			Map<String, String> map = new HashMap<>();
			map.put("success", "success");
			return map;
		} catch (UserNotFoundDbException e) {
			logger.error("Failed to deactivate user: {}", email);
			throw new UserNotFoundDbException("User Not Found");
		}
	}

	// Fetch users who are not admins
	public List<UsersDto> getNonAdminUsers() {
		logger.debug("Fetching candidate users (excluding roles={})", STAFF_ROLES);
		// Unpaged callers get the first page at the maximum size rather than the
		// whole table: the query this replaced took MySQL out of sort memory, and
		// an unbounded list is how it got there.
		return getCandidateUsers(PageRequest.of(0, MAX_PAGE_SIZE, EMAIL_ASC)).getContent();
	}

	/** Page size when a request does not ask for one. */
	public static final int DEFAULT_PAGE_SIZE = 20;
	/** Ceiling on a requested page size, so one caller cannot ask for the table. */
	public static final int MAX_PAGE_SIZE = 100;

	/**
	 * Email ascending — the only ordering that makes these pages stable.
	 *
	 * <p>Email is the primary key, so it is unique and never null. Paging an
	 * unordered query lets the database return rows in any order per call, which
	 * shows one account on two pages and hides another entirely.</p>
	 */
	private static final Sort EMAIL_ASC = Sort.by(Sort.Direction.ASC, "email");

	/** Clamps a requested page and size into something safe to execute. */
	public static Pageable toPageable(Integer page, Integer size) {
		int resolvedPage = (page == null || page < 0) ? 0 : page;
		int requested = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : size;
		return PageRequest.of(resolvedPage, Math.min(requested, MAX_PAGE_SIZE), EMAIL_ASC);
	}

	/** One page of candidates — every account holding no staff role. */
	public Page<UsersDto> getCandidateUsers(Pageable pageable) {
		return toDtoPage(userRepository.findPageExcludingActiveRoleIn(STAFF_ROLES, pageable), pageable);
	}

	/** One page of staff — every account holding ADMIN or SUPER_ADMIN. */
	public Page<UsersDto> getStaffUsersPage(Pageable pageable) {
		return toDtoPage(userRepository.findPageByActiveRoleIn(STAFF_ROLES, pageable), pageable);
	}

	/**
	 * One page of the whole roster, for the admin user screen.
	 *
	 * <p>Staff and candidates in one sorted, filterable list. The two role-scoped
	 * endpoints keep their own semantics; this exists because merging two
	 * independent pagers client-side cannot give stable page boundaries — a row
	 * ends up on two pages or on none.</p>
	 *
	 * @param role   narrow to one role, or null for every account
	 * @param search free text matched against email and full name, or null
	 */
	public Page<UsersDto> getDirectory(com.rightpath.rbac.RoleName role, String search, Pageable pageable) {
		// Normalised here rather than in the query so the JPQL stays a plain
		// LIKE: null means "no text filter", and a blank box means the same.
		String term = (search == null || search.isBlank())
				? null
				: "%" + search.trim().toLowerCase() + "%";

		Page<UsersDto> page = (role == null)
				? userRepository.findDirectory(term, pageable)
				: userRepository.findDirectoryByRole(role, term, pageable);
		return toDtoPage(page, pageable);
	}

	/**
	 * Attaches roles to a page the query already projected.
	 *
	 * <p>Not {@code page.map(...)}: that maps one element at a time, so filling
	 * each row's roles individually would cost a query per row — a page of 100
	 * accounts turning into 100 round trips for one column. {@link #attachRoles}
	 * batches the whole page, and the total comes from the original page so the
	 * count reflects the query rather than the slice.</p>
	 */
	private Page<UsersDto> toDtoPage(Page<UsersDto> page, Pageable pageable) {
		return new PageImpl<>(attachRoles(page.getContent()), pageable, page.getTotalElements());
	}

	/** The roles that make an account staff rather than a candidate. */
	private static final java.util.List<com.rightpath.rbac.RoleName> STAFF_ROLES =
			java.util.List.of(com.rightpath.rbac.RoleName.SUPER_ADMIN, com.rightpath.rbac.RoleName.ADMIN);

	/**
	 * Every staff account — anyone holding ADMIN or SUPER_ADMIN.
	 *
	 * <p>Complements {@link #getNonAdminUsers()}: that one answers "who are the
	 * candidates?", this one "who runs this system?". Both carry roles, because
	 * on a staff list the role <em>is</em> the point.</p>
	 */
	public List<UsersDto> getStaffUsers() {
		logger.debug("Fetching staff users (roles={})", STAFF_ROLES);
		// Bounded like the candidate equivalent: the first page at the maximum
		// size, never the whole table. Staff lists are short, so in practice this
		// still returns everyone while refusing to grow without limit.
		return getStaffUsersPage(PageRequest.of(0, MAX_PAGE_SIZE, EMAIL_ASC)).getContent();
	}

	/**
	 * Creates an account and grants it a staff role in one transaction.
	 *
	 * <p>{@link #addUser(UsersDto)} hardcodes the USER role, which is right for
	 * self-registration and useless here. This runs the same duplicate, strength
	 * and encoding checks, then assigns the requested role instead — atomically,
	 * so a failure part-way cannot leave an account with no role at all, which
	 * would be a candidate login nobody intended to create.</p>
	 *
	 * <p>Deliberately sends no welcome email: that template greets a candidate
	 * and points at the candidate portal, which is the wrong thing to send an
	 * administrator. Whoever creates the account passes on the credentials.</p>
	 *
	 * @param usersDto the account to create; its password is encoded here
	 * @param role     the role to grant — caller is responsible for authorising it
	 * @throws UserAlreadyInDatabaseException if the email or mobile is taken
	 */
	@Transactional
	public void createStaffUser(UsersDto usersDto, com.rightpath.rbac.RoleName role) {
		logger.info("Creating staff account {} with role {}", usersDto.getEmail(), role);

		if (userRepository.existsByEmail(usersDto.getEmail())) {
			throw new UserAlreadyInDatabaseException("Email already exists");
		}
		if (userRepository.existsByMobileNumber(usersDto.getMobileNumber())) {
			throw new UserAlreadyInDatabaseException("Mobile number already exists");
		}

		passwordValidator.passwordSizeChecker(usersDto.getPassword());

		Users user = new Users(
				usersDto.getEmail(),
				usersDto.getFirstName(),
				usersDto.getLastName(),
				bCryptPasswordEncoder.encode(usersDto.getPassword()),
				// Staff accounts are created ready to use; a disabled admin
				// cannot sign in to be enabled by anyone.
				Boolean.TRUE,
				usersDto.getMobileNumber());
		userRepository.save(user);

		// Seeded by RbacSeedConfig, but created on demand for the same reason
		// addUser does it: a fresh database must not fail to make its first admin.
		com.rightpath.entity.Role roleEntity = roleRepository.findByName(role)
				.orElseGet(() -> {
					logger.warn("Role {} not found; creating it on the fly", role);
					com.rightpath.entity.Role r = new com.rightpath.entity.Role();
					r.setName(role);
					return roleRepository.save(r);
				});

		com.rightpath.entity.UserRole assignment = new com.rightpath.entity.UserRole();
		assignment.setUser(user);
		assignment.setRole(roleEntity);
		assignment.setActive(true);
		userRoleRepository.save(assignment);

		logger.info("Staff account {} created with role {}", user.getEmail(), role);
	}

	/**
	 * Attaches each user's active roles to an already-projected page.
	 *
	 * <p>Roles come from one batch query rather than a lookup per user: a role
	 * column would otherwise cost a query per row, which is a query per candidate
	 * on a screen built to show hundreds.</p>
	 *
	 * <p>Every user gets a list, empty where they hold no role — so a client can
	 * tell "no roles" from "roles were never looked up", which stays null.</p>
	 *
	 * <p>Takes DTOs, not entities. The list queries project the six columns a
	 * roster displays, which leaves the BCrypt hash and the LONGBLOB avatar
	 * unread rather than read and then discarded.</p>
	 */
	private List<UsersDto> attachRoles(List<UsersDto> users) {
		if (users.isEmpty()) {
			return List.of();
		}

		List<String> emails = users.stream().map(UsersDto::getEmail).collect(Collectors.toList());
		// Null-safe on both halves of the pair. Neither column is nullable, so a
		// null here means the data is already wrong — but Collectors.groupingBy
		// throws on a null key, which would turn one bad assignment row into a
		// 500 for the entire list. Skipping the row costs that user a badge;
		// throwing costs everyone the page.
		Map<String, List<String>> rolesByEmail = userRoleRepository.findActiveRolesByEmails(emails).stream()
				.filter(pair -> pair != null && pair.userEmail() != null && pair.role() != null)
				.collect(Collectors.groupingBy(
						UserRoleName::userEmail,
						Collectors.mapping(pair -> pair.role().name(), Collectors.toList())));

		users.forEach(user -> user.setRoles(rolesByEmail.getOrDefault(user.getEmail(), List.of())));
		return users;
	}

	// Check if email is already registered
	public boolean isEmailRegistered(String email) {
		boolean exists = userRepository.existsByEmail(email);
		logger.debug("Checking if email is registered ({}): {}", email, exists);
		return exists;
	}

	// Get profile image as byte array
	public byte[] getProfileImage(String email) {
	    Users user = userRepository.findByEmail(email)
	            .orElseThrow(() -> new UserNotFoundDbException("User not found with email: " + email));

	    return user.getProfileImage(); // may be null
	}

	// Fetch user by email
	public Users getUserByEmail(String email) {
		logger.debug("Fetching user by email: {}", email);
		return userRepository.findByEmail(email).orElseThrow(() -> {
			logger.error("User not found for email: {}", email);
			return new RuntimeException("User not found with email: " + email);
		});
	}

	// Allow user to change their password
	public String changePassword(String email, String oldPassword, String newPassword, String confirmPassword) {
		logger.info("Password change requested for: {}", email);
		Optional<Users> optionalUser = userRepository.findByEmail(email);
		if (optionalUser.isEmpty()) {
			logger.error("User not found: {}", email);
			throw new UserNotFoundDbException("User not found");
		}

		Users user = optionalUser.get();

		if (!newPassword.equals(confirmPassword)) {
			logger.warn("New password and confirm password do not match for user: {}", email);
			throw new IllegalArgumentException("New password and confirm password do not match");
		}

		if (!bCryptPasswordEncoder.matches(oldPassword, user.getPassword())) {
			logger.warn("Old password mismatch for user: {}", email);
			throw new IllegalArgumentException("Old password does not match");
		}

		passwordValidator.passwordSizeChecker(newPassword);
		user.setPassword(bCryptPasswordEncoder.encode(newPassword));
		userRepository.save(user);

		logger.info("Password changed successfully for: {}", email);
		return "Password updated successfully";
	}
}
