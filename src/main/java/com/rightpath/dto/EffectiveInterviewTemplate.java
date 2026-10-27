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
 * @param configured         whether a job actually set any of this, or it is all
 *                           defaults — for telling a recruiter which they are
 *                           looking at
 */
public record EffectiveInterviewTemplate(
        int minQuestions,
        int maxQuestions,
        InterviewDifficulty baselineDifficulty,
        boolean adaptiveDifficulty,
        boolean configured) {
}
