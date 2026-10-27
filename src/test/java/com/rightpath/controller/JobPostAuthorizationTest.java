package com.rightpath.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import com.rightpath.rbac.PermissionName;

/**
 * Guards the authorization wiring of the job-post write endpoints.
 *
 * <p>An endpoint that silently loses its {@link PreAuthorize} still passes every
 * functional test while being open to any authenticated caller, so the annotation
 * itself is asserted here. Whether a given role <em>holds</em> the permission is
 * seeded in {@code RbacSeedConfig}: SUPER_ADMIN gets every permission, ADMIN all but
 * the user-management ones, and the USER role an explicit list that does not include
 * job-post writes — so a candidate calling either endpoint is rejected with 403.</p>
 */
class JobPostAuthorizationTest {

	@Test
	void updateRequiresTheJobPostUpdatePermission() {
		assertEquals("hasAuthority('" + PermissionName.JOB_POST_UPDATE + "')",
				preAuthorizeOf("updateJobPost", Long.class, com.rightpath.dto.JobPostDTO.class));
	}

	@Test
	void deleteRequiresTheJobPostDeletePermission() {
		assertEquals("hasAuthority('" + PermissionName.JOB_POST_DELETE + "')",
				preAuthorizeOf("deleteJobPost", Long.class));
	}

	@Test
	void createStillRequiresTheJobPostCreatePermission() {
		assertEquals("hasAuthority('" + PermissionName.JOB_POST_CREATE + "')",
				preAuthorizeOf("createJobPost", com.rightpath.dto.JobPostDTO.class));
	}

	@Test
	void writePermissionsAreSeparateSoTheyCanBeGrantedIndependently() {
		assertEquals(3, Set.of(PermissionName.JOB_POST_CREATE, PermissionName.JOB_POST_UPDATE,
				PermissionName.JOB_POST_DELETE).size());
	}

	private static String preAuthorizeOf(String methodName, Class<?>... parameterTypes) {
		Method method;
		try {
			method = JobPostController.class.getMethod(methodName, parameterTypes);
		} catch (NoSuchMethodException e) {
			throw new AssertionError("endpoint method " + methodName + " not found", e);
		}
		PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
		assertNotNull(annotation, methodName + " must be guarded by @PreAuthorize");
		return annotation.value();
	}
}
