package com.rightpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.rightpath.entity.CodeSubmission;

/**
 * Repository interface for managing {@link CodeSubmission} entities.
 * Provides methods to query submissions based on user email, language, and question ID.
 */
@Repository
@EnableJpaRepositories
public interface CodeSubmissionRepository extends JpaRepository<CodeSubmission, Long> {

    /**
     * Finds all code submissions for a given user email and programming language.
     *
     * @param userEmail the email of the user
     * @param language the programming language
     * @return list of matching code submissions
     */
    List<CodeSubmission> findByUserEmailAndLanguage(String userEmail, String language);

    /**
     * Finds all code submissions for a specific user.
     *
     * @param userEmail the email of the user
     * @return list of all submissions by the user
     */
    List<CodeSubmission> findByUserEmail(String userEmail);

    /**
     * Finds all submissions for a specific question.
     *
     * @param questionId the ID of the question
     * @return list of code submissions for the question
     */
    List<CodeSubmission> findByQuestionId(String questionId);

    /**
     * Finds all submissions by a user for a specific question.
     *
     * @param userEmail the email of the user
     * @param questionId the ID of the question
     * @return list of code submissions by the user for the given question
     */
    List<CodeSubmission> findByUserEmailAndQuestionId(String userEmail, String questionId);
    
  
    /**
     * One candidate's last run at each question, on every attempt they were
     * given.
     *
     * <p>Grouped by the assessment for the same reason as
     * {@link #findLatestSubmissionsByJobPrefix}: a re-sit reuses the question ids
     * of the sitting before it, so without it the newer attempt's rows win the
     * MAX(id) and the earlier attempt's runs are never returned.</p>
     */
    @Query("""
    	    SELECT cs FROM CodeSubmission cs
    	    WHERE cs.userEmail = :userEmail
    	      AND cs.jobPrefix = :jobPrefix
    	      AND cs.id IN (
    	          SELECT MAX(c2.id)
    	          FROM CodeSubmission c2
    	          WHERE c2.userEmail = :userEmail
    	            AND c2.jobPrefix = :jobPrefix
    	          GROUP BY c2.questionId, c2.assessmentId
    	      )
    	    ORDER BY cs.id DESC
    	""")
    	List<CodeSubmission> findLatestSubmissionsByUserEmailAndJobPrefix(
    	        @Param("userEmail") String userEmail,
    	        @Param("jobPrefix") String jobPrefix);
    
    Optional<CodeSubmission> findTopByUserEmailAndJobPrefixAndQuestionIdOrderByIdDesc(
    	    String userEmail, String jobPrefix, String questionId);

    
    List<CodeSubmission> findByUserEmailAndJobPrefixAndQuestionIdAndAssessmentId(
            String userEmail,
            String jobPrefix,
            String questionId,
            String assessmentId
    );

    /**
     * The last run each candidate made at each question, on every attempt they
     * were given.
     *
     * <p>Grouped by the assessment as well as the question. Without that, the
     * group is one row per candidate per question for the whole job — and a
     * re-sit reuses the same question ids as the sitting before it, so the newer
     * attempt won the MAX(id) and the earlier attempt's runs were never returned
     * at all. They were never deleted; this query hid them, and the review screen
     * showed the first attempt with none of its test results.</p>
     *
     * <p>Rows recorded before the assessment was stamped on them carry a null
     * assessment id and group together exactly as they always did, so historic
     * data reads unchanged.</p>
     */
    @Query("""
        SELECT cs FROM CodeSubmission cs
        WHERE cs.jobPrefix = :jobPrefix
          AND cs.id IN (
              SELECT MAX(c2.id)
              FROM CodeSubmission c2
              WHERE c2.jobPrefix = :jobPrefix
              GROUP BY c2.userEmail, c2.questionId, c2.assessmentId
          )
        ORDER BY cs.userEmail, cs.id DESC
    """)
    List<CodeSubmission> findLatestSubmissionsByJobPrefix(@Param("jobPrefix") String jobPrefix);

}
