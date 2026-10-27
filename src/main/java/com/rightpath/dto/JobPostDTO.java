package com.rightpath.dto;

import lombok.*;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Job posting payload, used for both create and update and returned by the
 * listing endpoints.
 *
 * <p>Constraints here are enforced on {@code POST /api/jobs/post} and
 * {@code PUT /api/jobs/post/{id}} alike, so an edit cannot introduce data a
 * create would have rejected.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPostDTO {

	/**
	 * Server-assigned identifier. Populated on read so clients can address a
	 * posting ({@code PUT /api/jobs/post/{id}}); ignored on write — the id comes
	 * from the path, and {@code POST} always assigns a fresh one.
	 */
	private Long id;

	@NotBlank(message = "Job title is mandatory")
	private String jobTitle;

	/**
	 * Immutable once the posting exists: applications, assessments, evaluation
	 * categories and the public apply link all key off it. On create this is the
	 * human part of the generated code ({@code FE-DEV} → {@code FE-DEV-005}); on
	 * update it must match the stored value (or be omitted), else the request is
	 * rejected with {@code JOB_PREFIX_IMMUTABLE}.
	 */
	/**
	 * Letters, digits, hyphens and underscores only.
	 *
	 * <p>The prefix becomes a path segment on a dozen endpoints
	 * ({@code /api/prompts/{prefix}}, {@code /api/job-applications/byJobPrefix/{prefix}}
	 * and the rest), so a character that means something in a URL breaks every
	 * one of them. A job created as {@code DEV-2026/29} produced the code
	 * {@code DEV-2026/29-055}, whose slash split one path segment into two —
	 * and every lookup for that job answered 404 with nothing on screen to
	 * explain why.</p>
	 *
	 * <p>Checked at creation because the code is permanent: applications,
	 * assessments and evaluation categories all key off it, so a prefix that
	 * cannot be addressed cannot be repaired without touching all of them.</p>
	 */
	@jakarta.validation.constraints.Pattern(
			regexp = "^[A-Za-z0-9_-]+$",
			message = "Job prefix may contain only letters, numbers, hyphens and underscores.")
	private String jobPrefix;

	private String companyName;
	private String location;

	// Matches the column length; without this an over-long description fails in the
	// database as a 500 instead of a readable 400.
	@Size(max = 3000, message = "Job description must be at most 3000 characters")
	private String jobDescription;

	private String keySkills;
	private String experience;
	private String education;
	private String salaryRange;
	private String jobType;
	private String industry;
	private String department;
	private String role;

	@Min(value = 1, message = "Number of openings must be at least 1")
	private Integer numberOfOpenings;

	@Email(message = "Contact email must be a valid email address")
	private String contactEmail;

	private LocalDate applicationDeadline;

	/**
	 * When this posting was created, in the business timezone.
	 *
	 * Read-only: the server sets it at creation and ignores whatever a client
	 * sends. Exposed so admins can see how long a job has been open when reading
	 * its applications.
	 */
	private LocalDate createdAt;
}
