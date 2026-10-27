package com.rightpath.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.config.AuthProperties;
import com.rightpath.dto.AccessTokenResponse;
import com.rightpath.dto.ApiResponse;
import com.rightpath.dto.LoginRequest;
import com.rightpath.dto.LoginResponse;
import com.rightpath.dto.MessageResponse;
import com.rightpath.dto.MeResponse;
import com.rightpath.dto.PaginatedResponse;
import com.rightpath.dto.UserInfo;
import com.rightpath.dto.CreateStaffUserRequest;
import com.rightpath.dto.UsersDto;
import com.rightpath.rbac.RoleName;
import com.rightpath.entity.Users;
import com.rightpath.exceptions.InvalidRefreshTokenException;
import com.rightpath.repository.OtpRepository;
import com.rightpath.service.OtpService;
import com.rightpath.service.impl.LoginService;
import com.rightpath.service.impl.RefreshTokenService;
import com.rightpath.service.impl.TokenFacade;
import com.rightpath.service.impl.UserDetailsServiceImpl;
import com.rightpath.util.RefreshCookieFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class AuthenthicationController {

	private static final Logger logger = LoggerFactory.getLogger(AuthenthicationController.class);

	@Autowired
	private UserDetailsServiceImpl userDetailsServiceImpl;

	private final OtpService otpService;

	private final RefreshTokenService refreshTokenService;
	private final TokenFacade tokenFacade;
	private final RefreshCookieFactory refreshCookieFactory;
	private final AuthProperties props;
	private final LoginService loginService;

	public AuthenthicationController(RefreshTokenService refreshTokenService, TokenFacade tokenFacade,
			RefreshCookieFactory refreshCookieFactory, AuthProperties props, LoginService loginService,
			OtpService otpService) {
		this.refreshTokenService = refreshTokenService;
		this.tokenFacade = tokenFacade;
		this.refreshCookieFactory = refreshCookieFactory;
		this.props = props;
		this.loginService = loginService;
		this.otpService = otpService;
	}

	/**
	 * Register a new user.
	 */
	@PostMapping("/register")
	public ResponseEntity<Map<String, String>> addUser(@Valid @RequestBody UsersDto usersDto) {
		logger.info("Registering user with email: {}", usersDto.getEmail());
		return ResponseEntity.ok(userDetailsServiceImpl.addUser(usersDto));
	}

//	/**
//	 * Authenticate user login.
//	 */
//	@PostMapping("/login")
//	public ResponseEntity<Map<String, String>> login(@Valid @RequestBody Login loginUser) {
//		logger.info("Login attempt for email: {}", loginUser.getEmail());
//		return ResponseEntity.ok(userDetailsServiceImpl.getLoginDetails(loginUser));
//	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest http) {
		var result = loginService.login(request, http);
		logger.info("V2 login success");
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, result.refreshCookie().toString())
				.body(ApiResponse.ok(result.body()));
	}

	@PostMapping("/refresh")
	public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(HttpServletRequest http) {
		String token = extractCookie(http, props.getRefresh().getCookieName());
		// Backward/forward compatibility: some environments use "refresh_token".
		if (token == null || token.isBlank()) {
			token = extractCookie(http, "refresh_token");
		}
		if (token == null || token.isBlank()) {
			throw new InvalidRefreshTokenException("Missing refresh token cookie");
		}

		var rotated = refreshTokenService.rotate(token, http.getRemoteAddr(), http.getHeader("User-Agent"));

		AccessTokenResponse newAccess = tokenFacade.issueAccessTokenForSubject(rotated.userEmail());

		var cookie = refreshCookieFactory.build(rotated.rawToken());
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(ApiResponse.ok(newAccess));
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<MessageResponse>> logout(HttpServletRequest http) {
		String token = extractCookie(http, props.getRefresh().getCookieName());
		if (token == null || token.isBlank()) {
			token = extractCookie(http, "refresh_token");
		}
		refreshTokenService.revokeByRawTokenIfPresent(token);

		var cleared = refreshCookieFactory.clear();
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleared.toString())
				.body(ApiResponse.ok(new MessageResponse("Logged out")));
	}

	@GetMapping("/me")
	public ResponseEntity<ApiResponse<MeResponse>> me(Authentication authentication) {
		if (authentication == null || authentication.getName() == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new ApiResponse<>(false, null, java.time.Instant.now()));
		}

		var roles = new java.util.LinkedHashSet<String>();
		var permissions = new java.util.LinkedHashSet<String>();

		if (authentication.getAuthorities() != null) {
			for (var a : authentication.getAuthorities()) {
				if (a == null || a.getAuthority() == null) {
					continue;
				}
				String auth = a.getAuthority();
				if (auth.startsWith("ROLE_")) {
					roles.add(auth.substring("ROLE_".length()));
				} else {
					permissions.add(auth);
				}
			}
		}

		Users userEntity = userDetailsServiceImpl.getUserByEmail(authentication.getName());
		UserInfo userInfo = new UserInfo(
				userEntity.getEmail(),
				userEntity.getFirstName(),
				userEntity.getLastName(),
				userEntity.getMobileNumber(),
				userEntity.getAlternativeMobileNumber()
		);

		return ResponseEntity.ok(ApiResponse.ok(new MeResponse(userInfo, roles, permissions)));
	}

	/**
	 * Activate user account by email (Admin only).
	 */
	@PostMapping("/updateActive")
	@PreAuthorize("hasAuthority('USER_ACTIVATE')")
	public ResponseEntity<Map<String, String>> activeByEmail(@RequestParam("email") String email) {
		logger.info("Activating user with email: {}", email);
		return ResponseEntity.ok(userDetailsServiceImpl.updateActive(email));
	}

	/**
	 * Deactivate user account by email (Admin only).
	 */
	@PostMapping("/updateDeactive")
	@PreAuthorize("hasAuthority('USER_DEACTIVATE')")
	public ResponseEntity<Map<String, String>> deleteByEmail(@RequestParam("email") String email) {
		logger.info("Deactivating user with email: {}", email);
		return ResponseEntity.ok(userDetailsServiceImpl.updateDeactive(email));
	}

	/**
	 * Get all non-admin users (Admin only).
	 */
	/**
	 * One page of candidate accounts — everyone holding no staff role.
	 *
	 * <p>Paginated and ordered by email. It used to return the whole table, and
	 * the query behind it took MySQL out of sort memory (error 1038) on barely a
	 * hundred rows; the fix is the {@code EXISTS} rewrite in the repository plus
	 * this bound, not a larger sort buffer.</p>
	 *
	 * <p>{@code page} is zero-based and {@code size} defaults to
	 * {@value com.rightpath.service.impl.UserDetailsServiceImpl#DEFAULT_PAGE_SIZE},
	 * capped at {@value com.rightpath.service.impl.UserDetailsServiceImpl#MAX_PAGE_SIZE}
	 * — the same convention as the paginated job listing.</p>
	 */
	@GetMapping("/users")
	@PreAuthorize("hasAuthority('USER_LIST')")
	public ResponseEntity<PaginatedResponse<UsersDto>> getAllNonAdminUsers(
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		Pageable pageable = UserDetailsServiceImpl.toPageable(page, size);
		logger.info("Fetching candidate users page {} size {}", pageable.getPageNumber(), pageable.getPageSize());
		return ResponseEntity.ok(new PaginatedResponse<>(userDetailsServiceImpl.getCandidateUsers(pageable)));
	}

	/**
	 * Every staff account — anyone holding ADMIN or SUPER_ADMIN.
	 *
	 * <p>{@code /users} above deliberately excludes administrators, so before
	 * this there was no way to see who holds privileged access. Same
	 * {@code USER_LIST} authority as the candidate list: whoever may enumerate
	 * accounts may enumerate all of them.</p>
	 */
	/**
	 * One page of every account, optionally filtered by role and free text.
	 *
	 * <p>What the admin user screen reads. {@code /users} and {@code /users/staff}
	 * keep their own meanings for existing callers; this is the combined,
	 * paginated view, because two independent pagers cannot be merged into one
	 * stable list.</p>
	 *
	 * @param role   ADMIN, SUPER_ADMIN or USER; omitted means every role
	 * @param search matched against email and full name
	 */
	@GetMapping("/users/directory")
	@PreAuthorize("hasAuthority('USER_LIST')")
	public ResponseEntity<PaginatedResponse<UsersDto>> getUserDirectory(
			@RequestParam(required = false) RoleName role,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		Pageable pageable = UserDetailsServiceImpl.toPageable(page, size);
		logger.info("Fetching user directory role={} page={} size={}",
				role, pageable.getPageNumber(), pageable.getPageSize());
		return ResponseEntity.ok(
				new PaginatedResponse<>(userDetailsServiceImpl.getDirectory(role, search, pageable)));
	}

	@GetMapping("/users/staff")
	@PreAuthorize("hasAuthority('USER_LIST')")
	public ResponseEntity<PaginatedResponse<UsersDto>> getStaffUsers(
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		Pageable pageable = UserDetailsServiceImpl.toPageable(page, size);
		logger.info("Fetching staff users page {} size {}", pageable.getPageNumber(), pageable.getPageSize());
		return ResponseEntity.ok(new PaginatedResponse<>(userDetailsServiceImpl.getStaffUsersPage(pageable)));
	}

	/**
	 * Creates an administrator or super administrator.
	 *
	 * <p>Guarded by {@code ROLE_MANAGE}, which only SUPER_ADMIN holds — creating
	 * an account and handing it a staff role is the same privilege as granting
	 * that role to an existing account, so the two are gated identically.</p>
	 *
	 * <p>No self-service route reaches this: {@code /register} always creates a
	 * candidate, whatever the request body says.</p>
	 */
	@PostMapping("/users/staff")
	@PreAuthorize("hasAuthority('ROLE_MANAGE')")
	public ResponseEntity<Map<String, String>> createStaffUser(
			@Valid @RequestBody CreateStaffUserRequest request) {
		logger.info("Creating staff account {} with role {}", request.email(), request.role());

		// USER would make this endpoint a way to create candidates with none of
		// the registration flow's steps — no welcome mail, no OTP. Registration
		// owns that path.
		if (request.role() == RoleName.USER) {
			return ResponseEntity.badRequest()
					.body(Map.of("message", "Use registration to create a candidate account",
							"status", "error"));
		}

		userDetailsServiceImpl.createStaffUser(request.toUsersDto(), request.role());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("message", "success", "email", request.email()));
	}

	/**
	 * Generate OTP for email or mobile.
	 */
	@PostMapping("/generate-otp")
	public ResponseEntity<Map<String, String>> generateOtp(@RequestBody Map<String, String> request) throws Exception {
		String type = request.get("type");
		String value = request.get("value");

		if ("email".equalsIgnoreCase(type)) {
			boolean exists = userDetailsServiceImpl.isEmailRegistered(value);
			if (!exists) {
				logger.warn("Email not registered: {}", value);
				return ResponseEntity.badRequest().body(Map.of("error", "Email is not registered."));
			}
			otpService.generateAndSendOtp(value, null);
			logger.info("OTP sent to email: {}", value);
			return ResponseEntity.ok(Map.of("message", "OTP has been sent to your registered email."));
		} else if ("mobile".equalsIgnoreCase(type)) {
			otpService.generateAndSendOtp(null, value);
			logger.info("OTP sent to mobile: {}", value);
			return ResponseEntity.ok(Map.of("message", "OTP has been sent to your mobile number."));
		} else {
			logger.warn("Invalid OTP request type: {}", type);
			return ResponseEntity.badRequest().body(Map.of("error", "Invalid OTP delivery method."));
		}
	}

	/**
	 * Validate the provided OTP.
	 */
	@PostMapping("/validate-otp")
	public ResponseEntity<Map<String, String>> validateOtp(@RequestBody Map<String, String> request) {
		String email = request.get("email");
		String mobile = request.get("mobile");
		String otp = request.get("otp");

		if ((email == null && mobile == null) || otp == null) {
			logger.warn("OTP validation failed: missing parameters.");
			return ResponseEntity.badRequest().body(Map.of("message", "Email/Mobile and OTP are required."));
		}

		boolean isValid = otpService.validateOtp(email, mobile, otp);
		logger.info("OTP validation result for {}: {}", email != null ? email : mobile, isValid);
		String message = isValid ? "OTP is valid." : "Invalid or expired OTP.";
		return ResponseEntity.ok(Map.of("message", message));
	}

	/**
	 * Update password using OTP verification.
	 */
	@PutMapping("/update-password")
	public ResponseEntity<Map<String, Object>> updatePassword(@RequestParam(required = false) String email,
			@RequestParam(required = false) String mobile, @RequestParam String newPassword) {

		boolean isUpdated = otpService.validateOtpAndUpdatePassword(email, mobile, newPassword);
		if (isUpdated) {
			logger.info("Password updated for {}", email != null ? email : mobile);
			return ResponseEntity.ok(Map.of("success", true, "message", "Password updated successfully",
					"contactMethod", email != null ? "email" : "mobile"));
		} else {
			logger.warn("Password update failed for {}", email != null ? email : mobile);
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("success", false, "message", "Password update failed"));
		}
	}

	/**
	 * Get user profile image by email.
	 */
	@GetMapping("/profile-image/{email}")
	@PreAuthorize("hasAuthority('USER_READ')")
	public ResponseEntity<byte[]> getProfileImage(@PathVariable String email) {
	    byte[] image = userDetailsServiceImpl.getProfileImage(email);

	    if (image == null) {
	        return ResponseEntity.noContent().build();
	    }

	    return ResponseEntity.ok()
	            .contentType(MediaType.IMAGE_JPEG)
	            .body(image);
	}

	/**
	 * Get full user profile by email.
	 */
	@GetMapping("/profile-details/{email}")
	@PreAuthorize("hasAuthority('USER_READ')")
	public ResponseEntity<UsersDto> getUserDetails(@PathVariable String email) {
		Users user = userDetailsServiceImpl.getUserByEmail(email);
		UsersDto userDto = new UsersDto(user);
		logger.info("Profile details fetched for: {}", email);
		return ResponseEntity.ok(userDto);
	}

	/**
	 * Update user profile details.
	 */
	@PutMapping("/update/{email}")
	@PreAuthorize("hasAuthority('USER_UPDATE')")
	public ResponseEntity<Map<String, String>> updateUser(@PathVariable String email,
			@RequestParam(required = false) String firstName, @RequestParam(required = false) String lastName,
			@RequestParam(required = false) String mobileNumber,
			@RequestParam(required = false) String alternativeMobileNumber,
			@RequestParam(required = false) MultipartFile profileImage) {

		logger.info("Updating profile for: {}", email);
		return ResponseEntity.ok(userDetailsServiceImpl.updateUser(email, firstName, lastName, mobileNumber,
				alternativeMobileNumber, profileImage));
	}

	/**
	 * Change password (with old password verification).
	 */
	@PutMapping("/change-password")
	@PreAuthorize("hasAuthority('USER_UPDATE')")
	public ResponseEntity<Map<String, String>> changePassword(@RequestParam String email,
			@RequestParam String oldPassword, @RequestParam String newPassword, @RequestParam String confirmPassword) {

		logger.info("Password change requested for: {}", email);
		String message = userDetailsServiceImpl.changePassword(email, oldPassword, newPassword, confirmPassword);
		return ResponseEntity.ok(Map.of("message", message));
	}

	private String extractCookie(HttpServletRequest request, String cookieName) {
		if (request.getCookies() == null) {
			return null;
		}
		for (var c : request.getCookies()) {
			if (cookieName.equals(c.getName())) {
				return c.getValue();
			}
		}
		return null;
	}
}
