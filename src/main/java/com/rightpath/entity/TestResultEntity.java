package com.rightpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestResultEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Lob
	private String input;

	@Lob
	private String expectedOutput;

	@Lob
	private String actualOutput;
	
	@Column(columnDefinition = "TEXT")
    private String testCasesJson;

	@Column(nullable = true)
	private Boolean passed;

	/**
	 * Why this case ended the way it did, as an {@link com.rightpath.enums.ExecutionStatus}
	 * name. A false {@code passed} covers a wrong answer, a crash and a timeout
	 * alike; a reviewer looking at the attempt later needs to tell them apart.
	 * Null on rows written before per-case status was recorded.
	 */
	@Column(name = "status", length = 32)
	private String status;

	/** The one-line explanation shown to the candidate at the time. */
	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	/** Wall-clock milliseconds for this run. */
	@Column(name = "execution_time_ms")
	private Long executionTimeMs;

	   private String questionId;

	
	 @ManyToOne(fetch = FetchType.LAZY)
	    @JoinColumn(name = "submission_id", nullable = false)
	    private CodeSubmission submission;

	public String getInput() {
		return input;
	}

	public void setInput(String input) {
		this.input = input;
	}

	

	
}
