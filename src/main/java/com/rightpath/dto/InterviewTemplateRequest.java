package com.rightpath.dto;

import com.rightpath.enums.InterviewDifficulty;
import com.rightpath.enums.InterviewRound;

import lombok.Data;

/**
 * What the console sends to configure one round of one job's interview.
 *
 * <p>The two question counts are boxed on purpose. Null means "use the platform
 * default", which is how a recruiter clears an override — otherwise the only
 * way back from a custom budget would be to find out what the default was and
 * retype it, and it would stop tracking the platform if that ever changed.</p>
 */
@Data
public class InterviewTemplateRequest {

    private String jobPrefix;
    private InterviewRound round;
    private Integer minQuestions;
    private Integer maxQuestions;
    private InterviewDifficulty baselineDifficulty;

    /** Defaults to on, matching the entity — an omitted field must not silently disable it. */
    private boolean adaptiveDifficulty = true;
}
