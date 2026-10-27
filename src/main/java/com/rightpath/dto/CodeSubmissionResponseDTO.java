package com.rightpath.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.rightpath.enums.ExecutionStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The result of a run, as the exam screen renders it.
 *
 * <p>The per-case detail is in {@code testResults}; the fields added alongside it
 * are the summary a candidate reads first — did it compile, how many cases
 * passed, and how long the whole thing took.</p>
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class CodeSubmissionResponseDTO {
	private Long id;
	private String language;
	private String script;
	private List<TestCaseDTO> testResults;
	private LocalDateTime createdAt;
	private String userEmail;
	private String questionId;
	private Boolean passed;

	/**
	 * The assessment this run was made against.
	 *
	 * Carried so a re-sit's runs can be told from the original's. The column has
	 * always been written; leaving it out of the response left the review screens
	 * splitting attempts on timestamps alone, which filed an earlier attempt's work
	 * under the later one. Null on rows written before it was recorded.
	 */
	private String assessmentId;

	/**
	 * The submission's overall verdict: PASSED when every case passed, otherwise
	 * the most significant failure — a compile error outranks a timeout, which
	 * outranks a wrong answer.
	 */
	private ExecutionStatus status;

	/** Set when the whole submission failed before any test case ran, i.e. it did not compile. */
	private CodeErrorInfo errorInfo;

	private Integer passedCount;
	private Integer totalCount;

	/** Wall-clock time for compilation plus every run. */
	private Long executionTimeMs;
}
