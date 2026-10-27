package com.rightpath.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of archiving a job post, so the confirmation toast can state exactly what
 * happened — including how many applications were kept rather than destroyed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobPostDeletionDTO {

	/** Id of the archived posting. */
	private Long id;

	/** Prefix of the archived posting; remains reserved and cannot be reused. */
	private String jobPrefix;

	/** When it was archived, in the business timezone. */
	private LocalDateTime deletedAt;

	/**
	 * Applications still filed under the prefix. They are deliberately left intact —
	 * deletion never destroys candidate history — and stay reachable through the
	 * job-application endpoints.
	 */
	private long applicationsRetained;
}
