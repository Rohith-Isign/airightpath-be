package com.rightpath.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
		name = "job_posts",
		// The listing endpoint filters/sorts on the deadline on every request, and
		// narrows by job type whenever the type dropdown is used.
		indexes = {
				@Index(name = "idx_job_posts_application_deadline", columnList = "application_deadline"),
				@Index(name = "idx_job_posts_job_type", columnList = "job_type")
		})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	
	@Column(name = "job_prefix", unique = true)
	private String jobPrefix;
	private String jobTitle;
	private String companyName;
	private String location;

	@Column(length = 3000)
	private String jobDescription;

	private String keySkills;
	private String experience;
	private String education;
	private String salaryRange;

	private String jobType; // e.g. Full-time
	private String industry;
	private String department;
	private String role;
	private Integer numberOfOpenings;

	private String contactEmail;
	private LocalDate applicationDeadline;

	private LocalDate createdAt;

	/**
	 * When this posting was last edited, in the business timezone; null until the
	 * first edit. A timestamp rather than a date (unlike {@code createdAt}, whose
	 * DATE column predates this) because edits to a live posting are audit-relevant.
	 */
	private LocalDateTime updatedAt;

	/** Email of the admin who last edited this posting; null until the first edit. */
	private String updatedBy;

	/**
	 * When this posting was archived ("deleted"), in the business timezone; null while
	 * it is live — so {@code deletedAt IS NULL} is the liveness test.
	 *
	 * <p>Deletion is soft: the row stays so that applications, assessments, results and
	 * compiler submissions filed under {@link #jobPrefix} keep pointing at a real job
	 * and remain auditable, and so the prefix can never be reused (it is unique, and
	 * the archived row still holds it). Archived postings are excluded from every
	 * listing and from the candidate apply path.</p>
	 */
	private LocalDateTime deletedAt;

	/** Email of the admin who archived this posting; null while it is live. */
	private String deletedBy;

	@OneToMany(mappedBy = "jobPost", cascade = CascadeType.ALL, orphanRemoval = true)
	@JsonIgnore
	private Set<JobApplicationForCandidate> jobApplications = new HashSet<>();

	
}
