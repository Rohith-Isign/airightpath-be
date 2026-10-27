package com.rightpath.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * That a candidate was given a question, on an assessment, for a job.
 *
 * <p>One row per question per assessment, so a candidate given the same question twice
 * (a repeat exam, or two papers that share it) shows twice. Never edited: it is the record
 * of what was asked of whom.</p>
 */
@Entity
@Table(name = "question_bank_assignment",
		uniqueConstraints = @UniqueConstraint(name = "uk_question_bank_assignment", columnNames = { "item_id",
				"assessment_id" }),
		indexes = { @Index(name = "idx_question_bank_assignment_item", columnList = "item_id"),
				@Index(name = "idx_question_bank_assignment_assessment", columnList = "assessment_id"),
				@Index(name = "idx_question_bank_assignment_candidate", columnList = "candidate_email") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionBankAssignment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(name = "assessment_id", nullable = false)
	private Long assessmentId;

	@Column(name = "candidate_email", nullable = false)
	private String candidateEmail;

	@Column(name = "job_prefix")
	private String jobPrefix;

	@Column(name = "assigned_at")
	private LocalDateTime assignedAt;
}
