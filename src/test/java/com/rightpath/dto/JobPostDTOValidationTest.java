package com.rightpath.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * The field rules applied to {@link JobPostDTO}, which {@code POST /api/jobs/post}
 * and {@code PUT /api/jobs/post/{id}} both enforce — an edit must not be able to
 * introduce data a create would have rejected.
 */
class JobPostDTOValidationTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void startValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void stopValidator() {
		factory.close();
	}

	@Test
	void acceptsAWellFormedPosting() {
		assertEquals(Set.of(), violatedFields(valid()));
	}

	@Test
	void requiresAJobTitle() {
		JobPostDTO missing = valid();
		missing.setJobTitle(null);
		assertTrue(violatedFields(missing).contains("jobTitle"));

		JobPostDTO blank = valid();
		blank.setJobTitle("   ");
		assertTrue(violatedFields(blank).contains("jobTitle"));
	}

	@Test
	void requiresAtLeastOneOpening() {
		JobPostDTO none = valid();
		none.setNumberOfOpenings(0);
		assertTrue(violatedFields(none).contains("numberOfOpenings"));

		JobPostDTO negative = valid();
		negative.setNumberOfOpenings(-3);
		assertTrue(violatedFields(negative).contains("numberOfOpenings"));

		// Absent is allowed — the column is nullable and the form leaves it optional.
		JobPostDTO absent = valid();
		absent.setNumberOfOpenings(null);
		assertEquals(Set.of(), violatedFields(absent));
	}

	@Test
	void capsTheDescriptionAtTheColumnLength() {
		JobPostDTO tooLong = valid();
		tooLong.setJobDescription("x".repeat(3001));
		assertTrue(violatedFields(tooLong).contains("jobDescription"));

		JobPostDTO atLimit = valid();
		atLimit.setJobDescription("x".repeat(3000));
		assertEquals(Set.of(), violatedFields(atLimit));
	}

	@Test
	void rejectsAMalformedContactEmail() {
		JobPostDTO bad = valid();
		bad.setContactEmail("not-an-email");
		assertTrue(violatedFields(bad).contains("contactEmail"));
	}

	@Test
	void leavesTheImmutablePrefixToTheServiceRule() {
		// jobPrefix carries no bean-validation constraint: create derives the code from
		// it (and defaults when absent), and update compares it to the stored value.
		JobPostDTO noPrefix = valid();
		noPrefix.setJobPrefix(null);
		assertEquals(Set.of(), violatedFields(noPrefix));
	}

	private static Set<String> violatedFields(JobPostDTO dto) {
		return validator.validate(dto).stream()
				.map(ConstraintViolation::getPropertyPath)
				.map(Object::toString)
				.collect(Collectors.toSet());
	}

	private static JobPostDTO valid() {
		return JobPostDTO.builder()
				.jobPrefix("FE-DEV-2026-005")
				.jobTitle("Junior Software Developer")
				.companyName("Rightpath")
				.jobDescription("Build things.")
				.numberOfOpenings(2)
				.contactEmail("hr@example.test")
				.build();
	}
}
