package com.rightpath.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rightpath.enums.AssessmentType;

/**
 * An assessment as the exam screen needs it: the paper plus the timing that
 * decides how long the candidate has.
 *
 * <p>{@code minutesPerQuestion} and {@code durationMinutes} are omitted when
 * unset — an assessment assigned before per-question timing existed carries
 * neither, and the client then falls back to its default for the type.</p>
 *
 * @param id                 the assessment id
 * @param assessmentType     aptitude or coding
 * @param jobPrefix          the job identifier prefix
 * @param candidateEmail     the candidate the paper is assigned to
 * @param startTime          when the exam window opens
 * @param deadline           when the exam window closes
 * @param examAttended       whether the attempt has already been submitted
 * @param minutesPerQuestion the admin's per-question allowance, if chosen
 * @param durationMinutes    a fixed override that wins outright, if set
 * @param questions          the paper, as stored
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentContentDto(
        Long id,
        AssessmentType assessmentType,
        String jobPrefix,
        String candidateEmail,
        LocalDateTime startTime,
        LocalDateTime deadline,
        boolean examAttended,
        Integer minutesPerQuestion,
        Integer durationMinutes,
        List<Map<String, Object>> questions
) {
}
