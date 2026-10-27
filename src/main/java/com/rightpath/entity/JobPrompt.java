package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.PromptStage;
import com.rightpath.enums.PromptType;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"job_prefix", "prompt_type", "prompt_stage"}))
public class JobPrompt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_prefix", nullable = false)
	private String jobPrefix;

	/**
	 * Stored as text, not as a database ENUM.
	 *
	 * <p>Hibernate maps a Java enum to a native MySQL {@code ENUM(...)} listing
	 * the constants that existed when the table was created, and
	 * {@code ddl-auto: update} never alters an existing column definition. So
	 * adding {@code INTERVIEW_L2_TECHNICAL} and {@code INTERVIEW_L3_BEHAVIORAL}
	 * to {@link PromptType} left the column still spelling out
	 * {@code ('APTITUDE','CODING','INTERVIEW')}: saving a round prompt was
	 * rejected by the database and answered 500, so those prompts could never
	 * be configured at all.</p>
	 *
	 * <p>A varchar takes any constant the enum grows, which means the next round
	 * added here needs no migration. The existing column still has to be
	 * converted once by hand — see
	 * {@code docs/migration-job-prompt-type-varchar.md}.</p>
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "prompt_type", columnDefinition = "varchar(64)")
	private PromptType promptType;

	/** Text for the same reason as {@link #promptType}. */
	@Enumerated(EnumType.STRING)
	@Column(name = "prompt_stage", columnDefinition = "varchar(32)")
	private PromptStage promptStage;

	@Lob
	@Column(columnDefinition = "TEXT", nullable = false)
	private String prompt;

	// FK for integrity
	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "job_post_id", nullable = false)
	private JobPost jobPost;

	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		this.createdAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();
	}

	@PreUpdate
	protected void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
