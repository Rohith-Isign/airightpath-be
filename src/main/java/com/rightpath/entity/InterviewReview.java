package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.InterviewResult;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A person's read on a finished interview: what they thought, and whether they
 * are overturning the machine's verdict.
 *
 * <p>Held apart from {@link CandidateInterviewSchedule} rather than as two more
 * columns on it, because these are different kinds of fact. The schedule
 * records what the interview did; this records what somebody decided about it
 * afterwards. Keeping them separate means the AI's own result is never
 * overwritten — {@code interviewResult} still says what the model concluded,
 * and this says what was done about it, so a disagreement stays legible instead
 * of being flattened into one field nobody can unpick later.</p>
 *
 * <p>One row per interview. An override is a current position, not a running
 * log: the reviewer, the reason and the timestamp are replaced when it is
 * revised, and who last changed it is recorded on the row. A full audit trail
 * would be a second table, and is worth adding the day someone needs to ask
 * what the decision was last Tuesday.</p>
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"interview_schedule_id"}))
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InterviewReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_schedule_id", nullable = false)
    private Long interviewScheduleId;

    /** Who last touched this. Their email, as it appears on their account. */
    @Column(length = 255)
    private String reviewerEmail;

    /**
     * Free notes. Kept whether or not the result was overturned — agreeing with
     * the machine and saying why is a review too, and the next person to open
     * this needs to know it has already been looked at.
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * The result a person is imposing, or null to let the AI's stand.
     *
     * <p>Null is meaningful and is the default: a reviewer who leaves notes
     * without changing the outcome has not silently endorsed a pass.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private InterviewResult overriddenResult;

    /**
     * Why the result was overturned. Required whenever it is.
     *
     * <p>An override with no reason is unreviewable six weeks later, and this
     * is the one action in the interview flow that changes a hiring outcome
     * after the fact.</p>
     */
    @Column(columnDefinition = "TEXT")
    private String overrideReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** True when a person has imposed a result different from the machine's. */
    public boolean isOverridden() {
        return overriddenResult != null;
    }
}
