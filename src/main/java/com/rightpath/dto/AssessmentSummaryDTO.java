package com.rightpath.dto;

import java.time.LocalDateTime;

import com.rightpath.enums.AssessmentType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An assessment assignment stripped to the facts a reviewer's list needs.
 *
 * <p>The {@code Assessment} entity carries the question paper as a TEXT column
 * and the answer key as a BLOB. Fetching a whole job's assignments as entities
 * therefore ships every paper and every answer key to a screen that wants none
 * of them — megabytes to render a pass mark. This projection exists so
 * {@code GET /api/assessments/by-job-prefix} can answer for a whole cohort in
 * one response without loading either column.</p>
 *
 * <p>The fields are exactly what grading a results table needs: which paper,
 * whose, of what type, to what standard, and when the window opened.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentSummaryDTO {

    private Long id;

    private String candidateEmail;

    private AssessmentType assessmentType;

    private String jobPrefix;

    /**
     * The bar this paper is graded to, as stored — {@code null} on rows written
     * before the column existed. Deliberately not defaulted here: the clients
     * already apply {@link com.rightpath.entity.Assessment#DEFAULT_PASS_PERCENTAGE}
     * for a missing value, and substituting it server-side would make a row that
     * never set a bar indistinguishable from one that chose 60.
     */
    private Integer passPercentage;

    /** Server's own stamp for when the paper was handed over. */
    private LocalDateTime assignedAt;

    /** Scheduled opening of the exam window, as the admin typed it. */
    private LocalDateTime startTime;

    private LocalDateTime deadline;

    /** When the candidate actually opened the paper; null if they never did. */
    private LocalDateTime examStartedAt;

    private boolean examAttended;

    private boolean expired;
}
