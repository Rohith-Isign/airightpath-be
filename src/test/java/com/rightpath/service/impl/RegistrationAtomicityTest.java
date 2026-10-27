package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.dto.UsersDto;
import com.rightpath.entity.Role;
import com.rightpath.entity.Users;
import com.rightpath.exceptions.UserAlreadyInDatabaseException;
import com.rightpath.rbac.RoleName;
import com.rightpath.repository.RoleRepository;
import com.rightpath.repository.UserRoleRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.EmailService;
import com.rightpath.validator.PasswordValidator;

/**
 * Registration has to be all-or-nothing.
 *
 * <p>Email is the primary key of {@code users}, so a half-finished registration
 * is not a retryable state: the row is there, every later attempt answers 409
 * "Email already exists", and the candidate has no account they can log into and
 * no way to clear it. Everything here guards the two ways that used to happen —
 * a failure after the insert leaving the row behind, and a mail outage failing a
 * registration that had already succeeded.</p>
 */
class RegistrationAtomicityTest {

	private static final String EMAIL = "candidate.one@example.com";
	private static final String MOBILE = "9998887770";

	private UsersRepository userRepository;
	private RoleRepository roleRepository;
	private UserRoleRepository userRoleRepository;
	private EmailService emailService;
	private UserDetailsServiceImpl service;

	@BeforeEach
	void setUp() {
		userRepository = mock(UsersRepository.class);
		roleRepository = mock(RoleRepository.class);
		userRoleRepository = mock(UserRoleRepository.class);
		emailService = mock(EmailService.class);

		PasswordEncoder encoder = mock(PasswordEncoder.class);
		when(encoder.encode(anyString())).thenReturn("hashed");

		service = new UserDetailsServiceImpl();
		ReflectionTestUtils.setField(service, "userRepository", userRepository);
		ReflectionTestUtils.setField(service, "roleRepository", roleRepository);
		ReflectionTestUtils.setField(service, "userRoleRepository", userRoleRepository);
		ReflectionTestUtils.setField(service, "emailService", emailService);
		ReflectionTestUtils.setField(service, "bCryptPasswordEncoder", encoder);
		ReflectionTestUtils.setField(service, "passwordValidator", mock(PasswordValidator.class));

		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(false);
		when(roleRepository.findByName(RoleName.USER)).thenReturn(Optional.of(role()));
		when(userRoleRepository.findByUser_EmailAndRole_NameAndActiveTrue(EMAIL, RoleName.USER))
				.thenReturn(Optional.empty());
	}

	@Test
	void aMailOutageDoesNotFailARegistrationThatAlreadySucceeded() {
		doThrow(new RuntimeException("Failed to send email"))
				.when(emailService).sendSuccessRegistrationEmail(anyString(), anyString(), anyString(), anyString());

		Map<String, String> result = service.addUser(newUser());

		// The account is created and the caller is told so. Throwing here is what
		// left the row behind and turned every retry into a 409.
		assertEquals("success", result.get("message"));
		verify(userRepository).save(any(Users.class));
	}

	@Test
	void registrationIsTransactionalSoAFailedRoleAssignmentTakesTheUserWithIt() throws NoSuchMethodException {
		// The rollback itself is the container's job; what this pins is that the
		// method still asks for one. Drop the annotation and the bug returns silently.
		assertNotNull(UserDetailsServiceImpl.class
				.getMethod("addUser", UsersDto.class)
				.getAnnotation(Transactional.class),
				"addUser must run in a transaction: the user row and its default role have to commit together");
	}

	@Test
	void aFailureAssigningTheDefaultRoleIsNotSwallowed() {
		when(roleRepository.findByName(RoleName.USER)).thenThrow(new IllegalStateException("role store down"));

		// It must propagate so the transaction rolls the user row back, rather than
		// leaving an account with no authorities that reports 409 ever after.
		assertThrows(IllegalStateException.class, () -> service.addUser(newUser()));
		verify(emailService, never()).sendSuccessRegistrationEmail(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	void anAlreadyRegisteredEmailIsRejectedBeforeAnythingIsWritten() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

		assertEquals("Email already exists",
				assertThrows(UserAlreadyInDatabaseException.class, () -> service.addUser(newUser())).getMessage());

		// Email is the primary key, so reaching save() here would overwrite the
		// existing account rather than be rejected by the database.
		verify(userRepository, never()).save(any(Users.class));
	}

	@Test
	void aTakenMobileNumberSaysSoRatherThanBlamingTheEmail() {
		when(userRepository.existsByMobileNumber(MOBILE)).thenReturn(true);

		assertEquals("Mobile number already exists",
				assertThrows(UserAlreadyInDatabaseException.class, () -> service.addUser(newUser())).getMessage());
		verify(userRepository, never()).save(any(Users.class));
	}

	private static UsersDto newUser() {
		UsersDto dto = new UsersDto();
		dto.setEmail(EMAIL);
		dto.setFirstName("Asha");
		dto.setLastName("Rao");
		dto.setPassword("Str0ng!pass");
		dto.setMobileNumber(MOBILE);
		dto.setEnabled(true);
		return dto;
	}

	private static Role role() {
		Role role = new Role();
		role.setName(RoleName.USER);
		return role;
	}
}
