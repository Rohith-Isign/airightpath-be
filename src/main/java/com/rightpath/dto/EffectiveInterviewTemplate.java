package com.rightpath.dto;

import com.rightpath.enums.InterviewDifficulty;

/**
 * The settings one interview actually runs on, after the job's template has
 * been laid over the platform defaults.
 *
 * <p>A resolved value rather than the stored row, so nothing downstream has to
 * ask "and what if this field is null". Every caller that reads a question
 * budget or a difficulty reads it from here, which is what stops one code path
 * honouring a job's configuration while another quietly uses the global
 * default.</p>
 *
 * @param minQuestions       fewest questions before the model may close
 * @param maxQuestions       hard ceiling on turns
 * @param baselineDifficulty how hard to pitch before any answers are in
 * @param adaptiveDifficulty whether difficulty follows the candidate's answers
 * @param paused             whether the question limits and difficulty are switched off for analysis
 *                           ({@code interview.settings.paused}); the stored values are still reported
 * @param safetyCap          the turn a paused interview is stopped at regardless
 * @param configured         whether a job actually set any of this, or it is all
 *                           defaults — for telling a recruiter which they are
 *                           looking at
 */
public record EffectiveInterviewTemplate(
        int minQuestions,
        int maxQuestions,
        InterviewDifficulty baselineDifficulty,
        boolean adaptiveDifficulty,
        boolean configured,
        boolean paused,
        int safetyCap) {

    /** Settings in force: every caller from before the pause existed. */
    public EffectiveInterviewTemplate(int minQuestions, int maxQuestions, InterviewDifficulty baselineDifficulty,
            boolean adaptiveDifficulty, boolean configured) {
        this(minQuestions, maxQuestions, baselineDifficulty, adaptiveDifficulty, configured, false, maxQuestions);
    }

    /**
     * Fewest questions before the interviewer may close. While the settings are paused there is no floor: the
     * interviewer closes when it has covered what it needs to (the topic-coverage check still holds it open).
     */
    public int effectiveMin() {
        return paused ? 1 : minQuestions;
    }

    /** The turn the interview stops at. While paused, a safety stop well above any real interview. */
    public int effectiveMax() {
        return paused ? safetyCap : maxQuestions;
    }

    /** Whether difficulty follows the candidate's answers. Off while paused: the level progression decides depth. */
    public boolean effectiveAdaptive() {
        return !paused && adaptiveDifficulty;
    }
}
