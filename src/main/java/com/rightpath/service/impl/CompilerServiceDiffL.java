package com.rightpath.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.rightpath.dto.CodeErrorInfo;
import com.rightpath.dto.CodeSubmissionResponseDTO;
import com.rightpath.dto.TestCaseDTO;
import com.rightpath.entity.CodeSubmission;
import com.rightpath.entity.TestResultEntity;
import com.rightpath.enums.ExecutionStatus;
import com.rightpath.exceptions.CompilerException;
import com.rightpath.repository.CodeSubmissionRepository;
import com.rightpath.service.CompilerService1;
import com.rightpath.service.impl.CodeExecutionEngine.CaseOutcome;
import com.rightpath.service.impl.CodeExecutionEngine.ExecutionReport;
import com.rightpath.service.impl.CodeExecutionEngine.RunCase;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs a candidate's submission and records what happened.
 *
 * <p>The execution itself lives in {@link CodeExecutionEngine}, which compiles
 * once per submission rather than once per test case and puts a deadline on
 * every process. This class is what sits either side of that: turning a
 * submission into cases to run, persisting the verdict, and shaping the answer
 * the exam screen renders.</p>
 */
@Service
@Slf4j
public class CompilerServiceDiffL implements CompilerService1 {

	private static final Logger logger = LoggerFactory.getLogger(CompilerServiceDiffL.class);

	private static final Pattern PUBLIC_CLASS = Pattern
			.compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");
	private static final Pattern ANY_CLASS = Pattern.compile("(?m)^\\s*(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");

	private final CodeSubmissionRepository submissionRepo;
	private final CodeExecutionEngine executionEngine;
	private final ErrorClassifier errorClassifier;

	public CompilerServiceDiffL(CodeSubmissionRepository submissionRepo, CodeExecutionEngine executionEngine,
			ErrorClassifier errorClassifier) {
		this.submissionRepo = submissionRepo;
		this.executionEngine = executionEngine;
		this.errorClassifier = errorClassifier;
	}

	/**
	 * Compiles the submission once, runs it against the given cases, saves the
	 * result and returns what the exam screen should show.
	 *
	 * <p>This is the single entry point for all three shapes of request — a set of
	 * test cases, a candidate's own custom input, or neither — because they only
	 * differ in what gets fed to stdin and whether there is anything to compare
	 * the output against.</p>
	 *
	 * @param submission  the submission to run and persist
	 * @param testCases   the cases to run; null or empty means a single free run
	 * @param customInput stdin for a free run; ignored when test cases are present
	 * @return the per-case results plus the overall verdict
	 */
	public CodeSubmissionResponseDTO runSubmission(CodeSubmission submission, List<TestCaseDTO> testCases,
			String customInput) {

		validateSubmission(submission);
		submission.setAttempted(true);
		if (submission.getCreatedAt() == null) {
			submission.setCreatedAt(LocalDateTime.now());
		}

		boolean graded = testCases != null && !testCases.isEmpty();
		List<RunCase> runCases = graded
				? testCases.stream().map(tc -> new RunCase(nullToEmpty(tc.getInput()), tc.getExpectedOutput())).toList()
				: List.of(RunCase.freeRun(nullToEmpty(customInput)));

		ExecutionReport report = executionEngine.execute(submission.getScript(), submission.getLanguage(), runCases);

		logger.info("Ran {} case(s) for {} in {}ms - verdict {}", report.outcomes().size(), submission.getUserEmail(),
				report.totalMs(), report.status());

		persist(submission, report, graded, testCases);
		return toResponse(submission, report, graded, testCases);
	}

	/**
	 * Writes one row per case so a reviewer can see later not just that a case
	 * failed but how.
	 */
	private void persist(CodeSubmission submission, ExecutionReport report, boolean graded,
			List<TestCaseDTO> testCases) {

		List<TestResultEntity> entities = new ArrayList<>(report.outcomes().size());
		for (int i = 0; i < report.outcomes().size(); i++) {
			CaseOutcome outcome = report.outcomes().get(i);
			TestResultEntity entity = new TestResultEntity();
			entity.setSubmission(submission);
			entity.setQuestionId(submission.getQuestionId());
			entity.setInput(outcome.testCase().input());
			entity.setExpectedOutput(outcome.testCase().expectedOutput());
			entity.setActualOutput(outcome.output());
			entity.setStatus(outcome.status().name());
			entity.setExecutionTimeMs(outcome.durationMs());
			entity.setErrorMessage(outcome.error() == null ? null : outcome.error().getMessage());
			// A free run has nothing to be right or wrong about, so it stays null
			// rather than claiming a pass or a failure it was never judged on.
			entity.setPassed(graded ? outcome.passed() : null);
			entities.add(entity);
		}

		if (submission.getTestResults() == null) {
			submission.setTestResults(entities);
		} else {
			// orphanRemoval is on, so replace in place rather than swapping the list.
			submission.getTestResults().clear();
			submission.getTestResults().addAll(entities);
		}
		submission.setPassed(graded && report.status() == ExecutionStatus.PASSED);
		submissionRepo.save(submission);
	}

	private CodeSubmissionResponseDTO toResponse(CodeSubmission submission, ExecutionReport report, boolean graded,
			List<TestCaseDTO> testCases) {

		List<TestCaseDTO> results = new ArrayList<>(report.outcomes().size());
		for (int i = 0; i < report.outcomes().size(); i++) {
			CaseOutcome outcome = report.outcomes().get(i);
			TestCaseDTO dto = new TestCaseDTO();
			dto.setInput(outcome.testCase().input());
			dto.setExpectedOutput(outcome.testCase().expectedOutput());
			dto.setActualOutput(outcome.output());
			dto.setQuestionId(submission.getQuestionId());
			dto.setStatus(outcome.status());
			dto.setExecutionTimeMs(outcome.durationMs());
			dto.setPassed(graded ? outcome.passed() : null);
			dto.setErrorInfo(outcome.error());
			if (graded && testCases != null && i < testCases.size()) {
				dto.setHidden(testCases.get(i).getHidden());
			}
			results.add(dto);
		}

		CodeSubmissionResponseDTO response = new CodeSubmissionResponseDTO();
		response.setId(submission.getId());
		response.setLanguage(submission.getLanguage());
		response.setScript(submission.getScript());
		response.setUserEmail(submission.getUserEmail());
		response.setQuestionId(submission.getQuestionId());
		response.setCreatedAt(submission.getCreatedAt());
		response.setTestResults(results);
		response.setStatus(report.status());
		response.setErrorInfo(report.compileError());
		response.setPassed(graded ? report.status() == ExecutionStatus.PASSED : null);
		response.setPassedCount((int) report.passedCount());
		response.setTotalCount(results.size());
		response.setExecutionTimeMs(report.totalMs());
		return response;
	}

	/**
	 * Kept for the {@link CompilerService1} contract. Prefer
	 * {@link #runSubmission} — it carries the per-case status and the structured
	 * error, which this signature has nowhere to put.
	 */
	@Override
	public List<TestResultEntity> executeCode(CodeSubmission submissionRequest) {
		List<TestCaseDTO> cases = submissionRequest.getTestResults() == null ? List.of()
				: submissionRequest.getTestResults().stream().map(tr -> {
					TestCaseDTO dto = new TestCaseDTO();
					dto.setInput(tr.getInput());
					dto.setExpectedOutput(tr.getExpectedOutput());
					return dto;
				}).collect(Collectors.toList());

		runSubmission(submissionRequest, cases, "");
		return submissionRequest.getTestResults();
	}

	@Override
	public void validateSubmission(CodeSubmission submissionRequest) {
		if (submissionRequest == null || submissionRequest.getScript() == null
				|| submissionRequest.getLanguage() == null) {
			throw new CompilerException("Invalid code request: script and language are both required.");
		}
	}

	/**
	 * Runs the code once and returns its output, throwing on any failure.
	 *
	 * <p>Kept for the interface contract. It flattens every kind of failure into
	 * one exception, which is exactly what {@link #runSubmission} exists to avoid.</p>
	 */
	@Override
	public String compileAndRunCode(String code, String input, String language) {
		ExecutionReport report = executionEngine.execute(code, language, List.of(RunCase.freeRun(input)));

		if (report.compileError() != null) {
			throw new CompilerException(report.compileError().getMessage());
		}
		CaseOutcome outcome = report.outcomes().get(0);
		if (outcome.status() != ExecutionStatus.PASSED) {
			throw new CompilerException(outcome.error() == null ? outcome.output() : outcome.error().getMessage());
		}
		return outcome.output();
	}

	@Override
	public String extractClassName(String code) {
		if (code == null) {
			return null;
		}
		Matcher matcher = PUBLIC_CLASS.matcher(code);
		if (matcher.find()) {
			return matcher.group(1);
		}
		matcher = ANY_CLASS.matcher(code);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** Runs the code against one ad-hoc input, recording the failure rather than throwing. */
	public TestResultEntity executeSingleInput(CodeSubmission submission, String customInput) {
		ExecutionReport report = executionEngine.execute(submission.getScript(), submission.getLanguage(),
				List.of(RunCase.freeRun(nullToEmpty(customInput))));
		CaseOutcome outcome = report.outcomes().get(0);

		TestResultEntity result = new TestResultEntity();
		result.setInput(customInput);
		result.setQuestionId(submission.getQuestionId());
		result.setSubmission(submission);
		result.setActualOutput(outcome.output());
		result.setStatus(outcome.status().name());
		result.setExecutionTimeMs(outcome.durationMs());
		result.setErrorMessage(outcome.error() == null ? null : outcome.error().getMessage());
		return result;
	}

	/**
	 * Re-reads a stored error string into structured form, for history rows
	 * written before the status was recorded alongside them.
	 */
	@Override
	public CodeErrorInfo parseErrorInfo(String rawError, String language) {
		if (rawError == null) {
			return null;
		}
		String cleaned = rawError.replaceFirst("^(?:Runtime|Compilation)\\s+Error:\\s*", "");
		try {
			return errorClassifier.runtimeError(cleaned, CodeExecutionEngine.Language.of(language), 1);
		} catch (RuntimeException e) {
			// An unknown language on an old row is not worth failing a history read.
			return errorClassifier.platformError(cleaned, null);
		}
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	// -----------------------------------------------------------------------
	// Submission history
	// -----------------------------------------------------------------------

	public List<CodeSubmission> getSubmissionsByUserAndLanguage(String userEmail, String language) {
		if (userEmail == null || userEmail.trim().isEmpty()) {
			throw new IllegalArgumentException("User email must not be null or empty.");
		}
		if (language == null || language.trim().isEmpty()) {
			throw new IllegalArgumentException("Language must not be null or empty.");
		}

		logger.info("Fetching code submissions for userEmail: {} and language: {}", userEmail, language);
		List<CodeSubmission> submissions = submissionRepo.findByUserEmailAndLanguage(userEmail.trim(), language.trim());
		logger.info("Found {} submissions for userEmail: {} and language: {}", submissions.size(), userEmail, language);

		return submissions;
	}

	public List<CodeSubmission> getSubmissionsByUserEmail(String userEmail) {
		if (userEmail == null || userEmail.trim().isEmpty()) {
			throw new IllegalArgumentException("User email must not be null or empty.");
		}

		logger.info("Fetching code submissions for userEmail: {}", userEmail);
		List<CodeSubmission> submissions = submissionRepo.findByUserEmail(userEmail.trim());
		logger.info("Found {} submissions for userEmail: {}", submissions.size(), userEmail);

		return submissions;
	}

	public List<CodeSubmission> getSubmissionsByUserAndQuestion(String userEmail, String questionId) {
		if (userEmail == null || userEmail.trim().isEmpty() || questionId == null || questionId.trim().isEmpty()) {
			throw new IllegalArgumentException("User email and question ID must not be null or empty.");
		}

		logger.info("Fetching submissions for user: {} and question: {}", userEmail, questionId);
		List<CodeSubmission> submissions = submissionRepo.findByUserEmailAndQuestionId(userEmail.trim(),
				questionId.trim());
		logger.info("Found {} submissions", submissions.size());

		return submissions;
	}

	public List<CodeSubmissionResponseDTO> getSubmissionsByPassStatus(String userEmail, Boolean passed) {
		List<CodeSubmission> submissions = submissionRepo.findByUserEmail(userEmail);

		return submissions.stream()
				.filter(sub -> sub.getTestResults() != null)
				.map(sub -> {
					CodeSubmissionResponseDTO dto = new CodeSubmissionResponseDTO();
					dto.setLanguage(sub.getLanguage());
					dto.setScript(sub.getScript());
					dto.setUserEmail(sub.getUserEmail());
					dto.setQuestionId(sub.getQuestionId());
					dto.setCreatedAt(sub.getCreatedAt());

					List<TestCaseDTO> testCaseDTOs = sub.getTestResults().stream()
							.filter(tr -> passed == null || passed.equals(tr.getPassed()))
							.map(tr -> toHistoryDto(tr, sub.getLanguage()))
							.collect(Collectors.toList());

					dto.setTestResults(testCaseDTOs);
					return dto;
				})
				.filter(dto -> !dto.getTestResults().isEmpty())
				.collect(Collectors.toList());
	}

	/**
	 * Projects a stored result, reconstructing the error detail from whatever the
	 * row holds — the recorded status where there is one, the old
	 * "Runtime Error: ..." prefix where there is not.
	 */
	private TestCaseDTO toHistoryDto(TestResultEntity result, String language) {
		TestCaseDTO dto = new TestCaseDTO();
		dto.setInput(result.getInput());
		dto.setExpectedOutput(result.getExpectedOutput());
		dto.setActualOutput(result.getActualOutput());
		dto.setPassed(result.getPassed());
		dto.setQuestionId(result.getQuestionId());
		dto.setExecutionTimeMs(result.getExecutionTimeMs());

		if (result.getStatus() != null) {
			try {
				dto.setStatus(ExecutionStatus.valueOf(result.getStatus()));
			} catch (IllegalArgumentException e) {
				logger.debug("Unrecognised stored status '{}' on test result {}", result.getStatus(), result.getId());
			}
		}

		if (result.getActualOutput() != null && result.getActualOutput().startsWith("Runtime Error")) {
			dto.setErrorInfo(parseErrorInfo(result.getActualOutput(), language));
		} else if (result.getErrorMessage() != null) {
			CodeErrorInfo error = new CodeErrorInfo();
			error.setCategory(dto.getStatus());
			error.setMessage(result.getErrorMessage());
			dto.setErrorInfo(error);
		}
		return dto;
	}

	public List<CodeSubmission> getLatestSubmissionsByUserEmailAndJobPrefix(String userEmail, String jobPrefix) {
		return submissionRepo.findLatestSubmissionsByUserEmailAndJobPrefix(userEmail, jobPrefix);
	}

	public Optional<CodeSubmission> getLatestSubmissionByUserEmailJobPrefixAndQuestionId(String userEmail,
			String jobPrefix, String questionId) {

		return submissionRepo.findTopByUserEmailAndJobPrefixAndQuestionIdOrderByIdDesc(userEmail, jobPrefix, questionId);
	}
}
