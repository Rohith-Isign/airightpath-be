package com.rightpath.dto;

import com.rightpath.enums.InterviewResult;

import lombok.Data;

/**
 * What a reviewer submits about a finished interview.
 *
 * <p>The reviewer's identity is not in here. It is taken from the authenticated
 * principal on the server — a client-supplied author on a record of who
 * overturned a hiring decision is a record of whoever the client said it
 * was.</p>
 */
@Data
public class InterviewReviewRequest {

    private String notes;

    /** Null leaves the AI's result standing; that is the default. */
    private InterviewResult overriddenResult;

    /** Required whenever {@link #overriddenResult} is set. */
    private String overrideReason;
}
