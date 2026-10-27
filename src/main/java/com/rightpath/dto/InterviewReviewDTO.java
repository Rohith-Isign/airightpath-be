package com.rightpath.dto;

import java.time.LocalDateTime;

import com.rightpath.entity.InterviewReview;
import com.rightpath.enums.InterviewResult;

/**
 * A reviewer's decision on one interview, as a screen needs it.
 *
 * @param reviewerEmail    who last reviewed it
 * @param notes            what they wrote
 * @param overriddenResult the result they imposed, or null to let the AI's stand
 * @param overrideReason   why, present only alongside an override
 * @param reviewedAt       when it was last changed
 */
public record InterviewReviewDTO(
        String reviewerEmail,
        String notes,
        InterviewResult overriddenResult,
        String overrideReason,
        LocalDateTime reviewedAt) {

    public static InterviewReviewDTO from(InterviewReview review) {
        return new InterviewReviewDTO(
                review.getReviewerEmail(),
                review.getNotes(),
                review.getOverriddenResult(),
                review.getOverrideReason(),
                review.getUpdatedAt());
    }
}
