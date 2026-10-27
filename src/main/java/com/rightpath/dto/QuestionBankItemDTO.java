package com.rightpath.dto;

import java.time.LocalDateTime;

/**
 * One question in the bank, with how it has been used.
 *
 * @param content        the question as a JSON object, in the shape the generator produced it
 * @param timesAssigned  how many assessments it has been on
 * @param uniqueCandidates how many different candidates were given it
 */
public record QuestionBankItemDTO(
		Long id,
		String jobPrefix,
		String jobTitle,
		String assessmentType,
		String content,
		String difficulty,
		Integer marks,
		String source,
		String createdBy,
		LocalDateTime createdAt,
		long timesAssigned,
		long uniqueCandidates,
		LocalDateTime lastAssignedAt) {
}
