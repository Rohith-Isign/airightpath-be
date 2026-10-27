package com.rightpath.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.rightpath.enums.AssessmentType;

import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Assessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AssessmentType assessmentType;
    @Column(name = "uploaded_by")
    private String uploadedBy;
    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(columnDefinition = "TEXT")
    private String questionPaper;

    @Nullable
    @Lob
    private byte[] answerKey;

    private boolean examAttended = false;
    private boolean expired = false;
    private boolean adminAcceptance = false;
    private String adminComments;

    private LocalDateTime assignedAt;
    private LocalDateTime startTime;
    private LocalDateTime deadline;

    /**
     * When the candidate actually opened this paper, as opposed to
     * {@link #startTime}, which is when the window was scheduled to open.
     *
     * <p>Stamped once, the first time the exam page reports the paper as
     * attended, and never overwritten — a reload part-way through must not look
     * like a fresh start. Null for attempts sat before this was recorded; those
     * can still be placed from the submission record, which carries how much of
     * the clock was left.</p>
     */
    @Column(name = "exam_started_at")
    private LocalDateTime examStartedAt;

    @OneToOne(mappedBy = "assessment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private Result result;

    @Column(name = "job_prefix")
    private String jobPrefix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_email", referencedColumnName = "email", insertable = false, updatable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private Users candidate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_prefix", referencedColumnName = "job_prefix", insertable = false, updatable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private JobPost jobPost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", referencedColumnName = "email", insertable = false, updatable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private Users uploader;

    @Column(length = 100)
    private String containerName;

    @Column(length = 100, unique = true)
    private String fileName;

    /**
     * Minutes the admin allowed per question when assigning this paper. The exam
     * clock is this multiplied by the real question count, so this is the
     * authoritative half of the allowance. Null on rows assigned before
     * per-question timing existed, which lets the client fall back to its default
     * for the assessment type.
     */
    @Column(name = "minutes_per_question")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer minutesPerQuestion;

    /**
     * A fixed exam length that overrides the per-question calculation outright.
     * Null unless someone deliberately pins the duration for this assignment.
     */
    @Column(name = "duration_minutes")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer durationMinutes;

    /**
     * Question count as counted at assign time. Informational only — the real
     * duration is recomputed from the stored paper when the exam opens, so a
     * mistyped count here cannot shorten a candidate's exam.
     */
    @Column(name = "question_count")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer questionCount;

    /**
     * Count times minutes as estimated at assign time. Informational, kept for
     * reporting on what the admin was shown when they assigned the paper.
     */
    @Column(name = "estimated_duration_minutes")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer estimatedDurationMinutes;

    /**
     * The mark, as a percentage, this paper must reach to pass.
     *
     * Held per assessment rather than per job so aptitude and coding can be
     * graded to different standards, and so changing the bar for a later round
     * cannot silently re-grade papers already sat. Defaults to
     * {@link #DEFAULT_PASS_PERCENTAGE}; rows written before this column existed
     * read as null and are treated as the default.
     */
    @Column(name = "pass_percentage")
    private Integer passPercentage = DEFAULT_PASS_PERCENTAGE;

    /** The bar applied when an assignment does not name one. */
    public static final int DEFAULT_PASS_PERCENTAGE = 60;

    /** Never null, so callers grading a result do not each repeat the fallback. */
    public int effectivePassPercentage() {
        return passPercentage == null ? DEFAULT_PASS_PERCENTAGE : passPercentage;
    }
}
