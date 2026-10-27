package com.rightpath.dto;

import com.rightpath.enums.ExecutionStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One test case and what the candidate's code did with it.
 *
 * <p>{@code passed} is kept as-is for existing clients; {@code status} is the
 * finer-grained answer — a false {@code passed} could mean a wrong answer, a
 * crash or a timeout, and those read very differently to someone mid-exam.</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestCaseDTO {

	private String input;
	private String expectedOutput;
	private String actualOutput;
	private Boolean passed;
	private String questionId;

	/** Why this test case ended the way it did. */
	private ExecutionStatus status;

	/** Wall-clock time for this run, so a candidate can see what is close to the limit. */
	private Long executionTimeMs;

	/** Whether this case is shown to the candidate or held back for scoring. */
	private Boolean hidden;

	private CodeErrorInfo errorInfo;

	// Getters and Setters
	public String getInput() {
		return input;
	}

	public void setInput(String input) {
		this.input = input;
	}

	public String getExpectedOutput() {
		return expectedOutput;
	}

	public void setExpectedOutput(String expectedOutput) {
		this.expectedOutput = expectedOutput;
	}

	public String getActualOutput() {
		return actualOutput;
	}

	public void setActualOutput(String actualOutput) {
		this.actualOutput = actualOutput;
	}
}
