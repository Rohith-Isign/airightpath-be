package com.rightpath.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rightpath.enums.InterviewRound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        columnNames = {"job_prefix", "interview_round", "category_name"}))
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EvaluationCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_prefix")
    private String jobPrefix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_prefix", referencedColumnName = "job_prefix", insertable = false, updatable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private JobPost jobPost;

    /**
     * The round these categories score, or null for a job that scores both the
     * same way.
     *
     * <p>Categories were per job, so the technical and behavioural rounds of
     * the same job were graded against one list. That is the wrong shape for
     * what the two rounds are: an L3 has nothing useful to say about
     * "Programming", and an L2 scored on "Ownership" is being marked on
     * something it never asked about. The grader produced a number for every
     * category regardless, which is how a round could come back with a score
     * for a subject that was never raised.</p>
     *
     * <p>Null rather than a default so that every job configured before this
     * existed keeps working unchanged: a round with no list of its own falls
     * back to the shared one, and only then to the platform's.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "interview_round", length = 32)
    private InterviewRound round;

    @Column(name = "category_name")
    private String categoryName;

    private double weight;

    private String description;

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
