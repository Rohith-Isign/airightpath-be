package com.rightpath.dto;

import java.time.LocalDateTime;

/** One time a question was given to a candidate. */
public record QuestionBankAssignmentDTO(Long assessmentId, String candidateEmail, String jobPrefix,
		LocalDateTime assignedAt) {
}
