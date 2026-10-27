package com.rightpath.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rightpath.dto.JobPromptRequest;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.JobPrompt;
import com.rightpath.enums.PromptStage;
import com.rightpath.enums.PromptType;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.JobPromptRepository;
import com.rightpath.service.JobPromptService;

import lombok.RequiredArgsConstructor;

@Service
public class JobPromptServiceImpl implements JobPromptService {

	private static final Logger log = LoggerFactory.getLogger(JobPromptServiceImpl.class);

	private final JobPromptRepository jobPromptRepository;
	private final JobPostRepository jobPostRepository;

	public JobPromptServiceImpl(JobPromptRepository jobPromptRepository, JobPostRepository jobPostRepository) {
		this.jobPromptRepository = jobPromptRepository;
		this.jobPostRepository = jobPostRepository;
	}

	@Override
	public JobPrompt saveJobPrompt(JobPromptRequest request) {

		JobPost jobPost = jobPostRepository.findByJobPrefix(request.getJobPrefix())
				.orElseThrow(() -> new RuntimeException("Invalid job prefix"));

		// Upsert: update existing prompt or create new one
		JobPrompt jobPrompt = jobPromptRepository
				.findByJobPrefixAndPromptTypeAndPromptStage(
						request.getJobPrefix(), request.getPromptType(), request.getPromptStage())
				.map(existing -> {
					existing.setPrompt(request.getPrompt());
					return existing;
				})
				.orElse(JobPrompt.builder()
						.jobPrefix(request.getJobPrefix())
						.promptType(request.getPromptType())
						.promptStage(request.getPromptStage())
						.prompt(request.getPrompt())
						.jobPost(jobPost)
						.build());

		return jobPromptRepository.save(jobPrompt);
	}

	@Override
	public List<JobPrompt> getPromptsByJobPrefix(String jobPrefix) {
		return jobPromptRepository.findAllByJobPrefix(jobPrefix);
	}

	/**
	 * A job's configured prompt, or a refusal naming what to configure.
	 *
	 * <p>There is no built-in prompt any more. {@code INTERVIEW}/{@code START}
	 * used to fall back to a system prompt compiled into the jar, which meant
	 * every job with nothing configured interviewed with the same hidden script
	 * — and an administrator editing prompts in the console saw no effect on a
	 * job whose prompt they had never saved. Aptitude and coding always required
	 * their prompt; the interview is now the same, and the message says where to
	 * add it.</p>
	 */
	@Override
	public String getPrompt(String jobPrefix, PromptType type, PromptStage stage) {
		return jobPromptRepository.findByJobPrefixAndPromptTypeAndPromptStage(jobPrefix, type, stage)
				.map(JobPrompt::getPrompt)
				.orElseThrow(() -> new IllegalStateException(
						"No " + type + " prompt is configured for this job (" + jobPrefix
								+ "). Add it under Manage AI Prompts before running this step."));
	}

	@Override
	public String getInterviewPrompt(String jobPrefix, com.rightpath.enums.InterviewRound round, PromptStage stage) {
		com.rightpath.enums.InterviewRound resolved = com.rightpath.enums.InterviewRound.orDefault(round);

		// The round's own prompt wins where one is configured.
		var roundPrompt = jobPromptRepository.findByJobPrefixAndPromptTypeAndPromptStage(
				jobPrefix, resolved.getPromptType(), stage);
		if (roundPrompt.isPresent()) {
			return roundPrompt.get().getPrompt();
		}

		// Otherwise the round-agnostic prompt, which is all a job configured
		// before rounds existed has — and what the console's "Interview
		// (shared)" tab writes.
		//
		// SUMMARY may be absent: callers read null as "grade with the built-in
		// criteria only". START may not. This used to swallow both, on the
		// footing that START always had a built-in fallback behind it; once that
		// fallback was removed, swallowing START handed the interviewer a null
		// system prompt and it interviewed with no instructions at all — worse
		// than refusing, because nothing said anything was wrong.
		if (stage == PromptStage.SUMMARY) {
			try {
				return getPrompt(jobPrefix, PromptType.INTERVIEW, stage);
			} catch (IllegalStateException noSummaryPrompt) {
				log.debug("No SUMMARY interview prompt for jobPrefix={} round={}", jobPrefix, resolved);
				return null;
			}
		}
		return getPrompt(jobPrefix, PromptType.INTERVIEW, stage);
	}

	@Override
	public String buildStartPrompt(String jobPrefix, String resumeSummary) {
		String template = getPrompt(jobPrefix, PromptType.INTERVIEW, PromptStage.START);
		return template.replace("{{resumeSummary}}", resumeSummary == null ? "" : resumeSummary);
	}

	@Override
	public String buildSummaryPrompt(String jobPrefix, String transcript) {
		String template = getPrompt(jobPrefix, PromptType.INTERVIEW, PromptStage.SUMMARY);
		return template.replace("{{transcript}}", transcript);
	}

}
