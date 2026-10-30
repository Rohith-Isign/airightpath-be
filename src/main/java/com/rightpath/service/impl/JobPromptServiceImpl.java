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
import com.rightpath.service.DefaultPrompts;
import com.rightpath.service.JobPromptService;

import lombok.RequiredArgsConstructor;

@Service
public class JobPromptServiceImpl implements JobPromptService {

	private static final Logger log = LoggerFactory.getLogger(JobPromptServiceImpl.class);

	private final JobPromptRepository jobPromptRepository;
	private final JobPostRepository jobPostRepository;
	private final DefaultPrompts defaultPrompts;

	public JobPromptServiceImpl(JobPromptRepository jobPromptRepository, JobPostRepository jobPostRepository,
			DefaultPrompts defaultPrompts) {
		this.jobPromptRepository = jobPromptRepository;
		this.jobPostRepository = jobPostRepository;
		this.defaultPrompts = defaultPrompts;
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
	 * A job's own prompt, else the built-in default for that slot, else a refusal naming what to configure.
	 *
	 * <p>An earlier version had no built-in prompt at all, because a hidden script answered for jobs with nothing
	 * saved and an administrator editing prompts saw no effect. The defaults are back, but in the open: they are
	 * the six texts in {@link DefaultPrompts}, and the Manage AI Prompts screen shows them as what a job is using
	 * until it is given one of its own. Only a slot with no default, or a default that failed to load, still
	 * refuses, and the message says where to add the prompt.</p>
	 */
	@Override
	public String getPrompt(String jobPrefix, PromptType type, PromptStage stage) {
		return jobPromptRepository.findByJobPrefixAndPromptTypeAndPromptStage(jobPrefix, type, stage)
				.map(JobPrompt::getPrompt)
				.or(() -> defaultPrompts.find(type, stage))
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
		var sharedPrompt = jobPromptRepository.findByJobPrefixAndPromptTypeAndPromptStage(
				jobPrefix, PromptType.INTERVIEW, stage);
		if (sharedPrompt.isPresent()) {
			return sharedPrompt.get().getPrompt();
		}

		// Last, the built-in default for this round: the L3 round gets the behavioural prompt, not the
		// technical one. Only a slot with no default, or one that failed to load, comes up empty. SUMMARY then
		// reads as "grade with the built-in criteria only"; START refuses, because swallowing it would hand the
		// interviewer a null system prompt and it would interview with no instructions at all.
		var builtIn = defaultPrompts.find(resolved.getPromptType(), stage);
		if (builtIn.isPresent()) {
			return builtIn.get();
		}
		if (stage == PromptStage.SUMMARY) {
			log.debug("No SUMMARY interview prompt for jobPrefix={} round={}", jobPrefix, resolved);
			return null;
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
