package com.rightpath.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.rightpath.dto.AssessmentSummaryDTO;
import com.rightpath.entity.Assessment;
import com.rightpath.enums.AssessmentType;
import jakarta.transaction.Transactional;

/**
 * JPA Repository for managing Assessment entities and their lifecycle operations.
 * 
 * <p>Provides both derived query methods and custom JPQL/native queries for:
 * <ul>
 *   <li>Assessment retrieval by various criteria</li>
 *   <li>Expiry status management</li>
 *   <li>Exam attendance tracking</li>
 *   <li>Complex multi-parameter lookups</li>
 * </ul>
 * 
 * <p>Note: All modifying queries are transactional and include proper locking hints
 * where needed for concurrent access scenarios.
 */
@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    /**
     * Finds all assessments associated with a candidate's email.
     * 
     * @param candidateEmail The email address of the candidate
     * @return List of matching assessments (empty if none found)
     * 
     * @implNote Uses Spring Data's derived query method naming convention
     */
    List<Assessment> findByCandidateEmail(String candidateEmail);

    /**
     * Retrieves assessments that have passed their deadline but aren't marked as expired.
     * 
     * @return List of overdue assessments needing expiry processing
     * 
     * @apiNote Used by scheduled expiry jobs
     * @see Assessment#expired
     */
    /**
     * Where each assessment's question paper lives, without loading the paper.
     *
     * <p>The entity carries the paper (TEXT) and the answer key (a LOB); reading every
     * assessment whole to learn which candidate got which file would pull all of it
     * through the connection. This is the handful of columns the question bank needs
     * to find each paper and attribute it.</p>
     */
    @Query("SELECT a.id AS id, a.candidateEmail AS candidateEmail, a.jobPrefix AS jobPrefix, "
            + "a.assessmentType AS assessmentType, a.assignedAt AS assignedAt, a.uploadedBy AS uploadedBy, "
            + "a.containerName AS containerName, a.fileName AS fileName FROM Assessment a")
    List<AssessmentPaperRef> findPaperRefs();

    @Query("SELECT a FROM Assessment a WHERE a.deadline < CURRENT_TIMESTAMP AND a.expired = false")
    List<Assessment> findExpiredAssessments();

    /**
     * Finds active (non-expired, non-attended) assessments for a candidate.
     * 
     * @param candidateEmail The candidate's email address
     * @return List of active assessments
     * 
     * @implNote Uses JPQL with explicit parameter binding
     */
    @Query("SELECT a FROM Assessment a WHERE a.candidateEmail = :candidateEmail " +
           "AND a.expired = false AND a.examAttended = false")
    List<Assessment> findActiveAssessments(@Param("candidateEmail") String candidateEmail);

    /**
     * Marks overdue assessments as expired in bulk.
     * 
     * @return Count of assessments updated
     * 
     * @apiNote Requires both @Modifying and @Transactional annotations
     * @see #findExpiredAssessments() 
     */
    @Query("UPDATE Assessment a SET a.expired = true " +
           "WHERE a.deadline < CURRENT_TIMESTAMP AND a.expired = false")
    @Modifying
    @Transactional
    int updateExpiredAssessments();

    /**
     * Finds the most recent assessment by composite key using method naming.
     * 
     * @param email Candidate email
     * @param jobPrefix Job identifier prefix
     * @param assessmentType Type of assessment
     * @return Optional containing the latest matching assessment
     * 
     * @implNote Uses Spring Data's method name derivation
     * @see #findLatestNative(String, String, String) 
     */
    Optional<Assessment> findTopByCandidateEmailAndJobPrefixAndAssessmentTypeOrderByAssignedAtDesc(
            String email,
            String jobPrefix,
            AssessmentType assessmentType
    );

    /**
     * Native SQL fallback for finding latest assessment (useful for complex queries).
     * 
     * @param email Candidate email
     * @param jobPrefix Job identifier prefix
     * @param assessmentType Type of assessment
     * @return Optional containing the latest matching assessment
     * 
     * @implNote Uses native SQL with explicit LIMIT for better control
     */
    @Query(value = """
            SELECT * FROM assessment
            WHERE candidate_email = :email
              AND job_prefix = :jobPrefix
              AND assessment_type = :type
            ORDER BY assigned_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Assessment> findLatestNative(
            @Param("email") String email,
            @Param("jobPrefix") String jobPrefix,
            @Param("type") String assessmentType
    );

    /**
     * Marks an assessment as attended by a candidate.
     * 
     * @param candidateEmail The attending candidate's email
     * @param assessmentId The assessment ID
     * 
     * @throws jakarta.persistence.OptimisticLockException if concurrent modification occurs
     * @apiNote Uses explicit parameter names for clarity
     */
    @Modifying
    @Transactional
    @Query("UPDATE Assessment a SET a.examAttended = true " +
           "WHERE a.id = :assessmentId AND a.candidateEmail = :candidateEmail")
    void markExamAttended(
            @Param("candidateEmail") String candidateEmail, 
            @Param("assessmentId") Long assessmentId
    );
    
    Optional<Assessment> findTopByJobPrefixAndCandidateEmailAndAssessmentTypeOrderByAssignedAtDesc(
            String jobPrefix, String candidateEmail, AssessmentType assessmentType);

    long countByCandidateEmailAndJobPrefixAndExamAttendedFalse(String candidateEmail, String jobPrefix);

    /**
     * The oldest assessment of a type that the candidate has not yet sat.
     *
     * <p>Used when a result arrives without an assessment id (an older client).
     * Resolving to an <em>unattended</em> row is the part that matters: a
     * re-assigned exam leaves two rows of the same type, and picking the first
     * of them regardless of attendance re-flagged an attempt already sat while
     * leaving the new one pending forever.</p>
     *
     * @param candidateEmail the candidate's email
     * @param jobPrefix      the job identifier prefix
     * @param assessmentType the type being submitted
     * @return the earliest still-unattended assessment, if any
     */
    Optional<Assessment> findTopByCandidateEmailAndJobPrefixAndAssessmentTypeAndExamAttendedFalseOrderByAssignedAtAsc(
            String candidateEmail,
            String jobPrefix,
            AssessmentType assessmentType
    );

    /**
     * Whether any assessment has been assigned to a candidate for a given job.
     * Used to guard the send-exam-link flow so a candidate is never marked
     * EXAM_SENT without an actual exam to take.
     *
     * @param candidateEmail the candidate's email
     * @param jobPrefix      the job identifier prefix
     * @return true if at least one assessment exists for the pair
     */
    boolean existsByCandidateEmailAndJobPrefix(String candidateEmail, String jobPrefix);

    /**
     * Every assignment on a job, as a projection rather than as entities.
     *
     * <p>Reviewing a job's results needs each candidate's pass marks and exam
     * windows. The only route to those was {@code getAssessments?candidateEmail=},
     * one request per candidate, so a job with 500 candidates cost 500 round
     * trips before the table could grade a single row. This answers the same
     * question once.</p>
     *
     * <p>A constructor expression, not {@code findByJobPrefix}: selecting
     * entities would load {@code questionPaper} (TEXT) and {@code answerKey}
     * (BLOB) for every assignment on the job, which is the bulk of the table and
     * none of what the caller wants. Naming the columns keeps both out of the
     * query entirely.</p>
     *
     * <p>Ordered oldest first per candidate so a re-assigned paper reads in the
     * order it was handed over — clients take the last of each type as the
     * attempt to grade.</p>
     *
     * @param jobPrefix the job identifier prefix
     * @return one row per assignment, empty when the job has none
     */
    @Query("""
            SELECT new com.rightpath.dto.AssessmentSummaryDTO(
                a.id, a.candidateEmail, a.assessmentType, a.jobPrefix,
                a.passPercentage, a.assignedAt, a.startTime, a.deadline,
                a.examStartedAt, a.examAttended, a.expired)
            FROM Assessment a
            WHERE a.jobPrefix = :jobPrefix
            ORDER BY a.candidateEmail ASC, a.assignedAt ASC, a.id ASC
            """)
    List<AssessmentSummaryDTO> findSummariesByJobPrefix(@Param("jobPrefix") String jobPrefix);

}