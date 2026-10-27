package com.rightpath.service;

import java.util.List;

import com.rightpath.dto.JobPromptRequest;
import com.rightpath.entity.JobPrompt;
import com.rightpath.enums.PromptStage;
import com.rightpath.enums.PromptType;

public interface JobPromptService {

	JobPrompt saveJobPrompt(JobPromptRequest request);

	List<JobPrompt> getPromptsByJobPrefix(String jobPrefix);

	String buildStartPrompt(String jobPrefix, String resumeSummary);

	String buildSummaryPrompt(String jobPrefix, String transcript);

	String getPrompt(String jobPrefix, PromptType type, PromptStage stage);

	/**
	 * The interview prompt for one round, falling back to the round-agnostic one.
	 *
	 * <p>Resolution order: the round's own prompt, then {@link PromptType#INTERVIEW},
	 * then whatever {@link #getPrompt} falls back to. A job configured before
	 * rounds existed has only the middle one, and must keep interviewing exactly
	 * as it did — so a missing round prompt is normal, not an error.</p>
	 *
	 * @param round the round being sat; null is treated as the default round
	 * @param stage START for the interviewer's instructions, SUMMARY for the grader's
	 * @return the prompt text, or null when the job has configured neither and
	 *         no built-in fallback applies
	 */
	String getInterviewPrompt(String jobPrefix, com.rightpath.enums.InterviewRound round, PromptStage stage);

}
