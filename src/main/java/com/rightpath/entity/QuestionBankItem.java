package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.AssessmentType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One exam question, kept on its own.
 *
 * <p>Questions used to exist only as lines inside the paper handed to each candidate, and
 * the paper lives in storage, one copy per candidate. So there was no answer to "have we
 * asked this before?", no way to find a question that was generated and never used, and no
 * count of how often one had been given. This is the question itself, once, identified by
 * a fingerprint of its wording so the same question arriving from a generator, an upload
 * and a stored paper is the same row.</p>
 */
@Entity
@Table(name = "question_bank_item",
		uniqueConstraints = @UniqueConstraint(name = "uk_question_bank_item", columnNames = { "job_prefix",
				"assessment_type", "content_hash" }),
		indexes = { @Index(name = "idx_question_bank_item_job", columnList = "job_prefix") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionBankItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_prefix", nullable = false)
	private String jobPrefix;

	@Enumerated(EnumType.STRING)
	@Column(name = "assessment_type", nullable = false, length = 16)
	private AssessmentType assessmentType;

	/** SHA-256 of the normalised wording; see {@code QuestionBankTracker#fingerprint}. */
	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	/** The question exactly as it appeared: one JSON object. */
	@Column(name = "content", columnDefinition = "LONGTEXT", nullable = false)
	private String content;

	private String difficulty;

	private Integer marks;

	/** AI_GENERATED when it first appeared from the generator; UPLOADED when it first came in on a paper. */
	@Column(nullable = false, length = 20)
	private String source;

	@Column(name = "created_by")
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
