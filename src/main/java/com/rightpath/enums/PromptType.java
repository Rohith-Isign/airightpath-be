package com.rightpath.enums;

/**
 * Which prompt a {@code JobPrompt} row holds.
 *
 * <p>The interview values double as the round selector. {@code JobPrompt} is
 * keyed on {@code (job_prefix, prompt_type, prompt_stage)}, so giving each round
 * its own type lets one job carry a separate prompt per round without a schema
 * change — and without inventing a second way to say "which interview".</p>
 */
public enum PromptType {
	APTITUDE,
	CODING,

	/**
	 * The original, round-agnostic interview prompt.
	 *
	 * <p>Kept as the fallback for every job configured before rounds existed:
	 * those jobs have one INTERVIEW prompt and no round-specific one, and must
	 * keep interviewing exactly as they did. New configuration should use the
	 * round-specific values below.</p>
	 */
	INTERVIEW,

	/** L2 — the technical round. */
	INTERVIEW_L2_TECHNICAL,

	/** L3 — the behavioural round, often called the HR round. */
	INTERVIEW_L3_BEHAVIORAL
}
