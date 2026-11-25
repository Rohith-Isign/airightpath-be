package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.InterviewRound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The list of what one job's role needs, for one round: the requirements every interview for it is scored against.
 *
 * <p>The grader used to write this list afresh on every evaluation, ten or fifteen items worded differently each time,
 * so the same interview re-evaluated came back with a different score, and two candidates for the same job were
 * measured against different lists. It is now written once, by the first evaluation for the job, and every later
 * evaluation and re-evaluation marks the same list. When the job's description, skills or experience change, the
 * hash no longer matches and a new list is made.</p>
 */
@Entity
@Table(name = "role_requirement_set",
        uniqueConstraints = @UniqueConstraint(columnNames = { "jobPrefix", "round", "jobHash" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleRequirementSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String jobPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewRound round;

    /** A hash of the job text the list was made from, so a changed job gets a new list. */
    @Column(nullable = false, length = 64)
    private String jobHash;

    /** The requirements: concept, requirement (SKILL, RESPONSIBILITY, EXPERIENCE), skill, importance and area. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String requirementsJson;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
    }
}
