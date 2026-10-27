package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.InterviewDifficulty;
import com.rightpath.enums.InterviewRound;

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
 * How one round of one job's interview is set up.
 *
 * <p>Everything here was previously a single number in {@code application.yml}
 * applying to every interview the platform ran: the same ten-to-twenty question
 * budget for a graduate screen and a senior technical round, and no way to say
 * that one of them should be pitched harder. Changing any of it meant a
 * redeploy, and changing it for one job was not possible at all.</p>
 *
 * <p>Rows are per {@code (jobPrefix, round)}, because the two rounds of the same
 * job are genuinely different interviews — a behavioural round that has to fit
 * the same twenty-question budget as a technical one spends most of it on
 * follow-ups. A job with no row runs on the yml defaults, so nothing has to be
 * configured for an interview to work.</p>
 *
 * <p>Topics and their weights are not here: they live in
 * {@link EvaluationCategory}, which is also keyed by round. Two tables rather
 * than one because a job has many categories and one template, and folding a
 * list into this row would mean parsing it back out everywhere it is read.</p>
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"job_prefix", "interview_round"}))
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InterviewTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_prefix", nullable = false)
    private String jobPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_round", length = 32, nullable = false)
    private InterviewRound round;

    /**
     * Fewest questions before the interviewer may close of its own accord.
     *
     * <p>Null means "use the platform default". Stored as a boxed Integer for
     * exactly that reason: zero is a meaningful answer to "how many questions
     * at minimum", so it cannot double as "unset".</p>
     */
    private Integer minQuestions;

    /** Hard ceiling on turns. Null means the platform default. */
    private Integer maxQuestions;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private InterviewDifficulty baselineDifficulty;

    /**
     * Whether difficulty moves with how the candidate is doing.
     *
     * <p>On by default, because an interview that adapts gets more signal out of
     * the same number of questions. Off is a real choice, not a kill switch: a
     * recruiter comparing a cohort against one fixed bar wants every candidate
     * asked at the same level, and an interview that eases off for whoever
     * struggles makes those scores incomparable.</p>
     */
    @Builder.Default
    private boolean adaptiveDifficulty = true;

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
}
