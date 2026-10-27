package com.rightpath.dto;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartInterviewRequest {
	private String email;
	@Column(nullable = false)
	private String jobPrefix;
	private String resumeSummary;
	 private Long fromDate;   // optional – Unix epoch milliseconds
	 private Long toDate;     // optional – Unix epoch milliseconds

	/**
	 * Which booked interview to start.
	 *
	 * <p>A candidate can have more than one outstanding at once — a repeat L2
	 * and an L3, say — and they choose from a list before reaching the interview
	 * screen. Without this the server fell back to whichever schedule was
	 * assigned most recently, discarding that choice and sometimes starting the
	 * wrong round.</p>
	 *
	 * <p>Optional, so callers that predate it keep working on the same
	 * most-recent fallback.</p>
	 */
	private Long scheduleId;
}