package com.rightpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;

/**
 * Repository interface for {@link JobApplicationForCandidate} entity.
 * Provides methods to fetch job applications based on user email, job prefix, and job post.
 */
@Repository
public interface JobApplicationForCandidateRepository extends JpaRepository<JobApplicationForCandidate, Long> {

    /**
     * Retrieves all job applications submitted by a user identified by email.
     *
     * @param email the email of the user
     * @return list of job applications submitted by the user
     */
    @Query("SELECT app FROM JobApplicationForCandidate app WHERE app.user.email = :email")
    List<JobApplicationForCandidate> findByUserEmail(@Param("email") String email);

    /**
     * Retrieves the candidate's job applications that actually carry a resume,
     * ordered newest-first. A candidate may have applied to several jobs (and so
     * have several rows); ordering by descending id — the identity key is
     * monotonic with creation — makes "which resume do we serve?" deterministic:
     * the most recently submitted application wins. Used by the view-resume flow.
     *
     * @param email the email of the candidate
     * @return resume-bearing applications, most recent first (empty if none)
     */
    @Query("SELECT app FROM JobApplicationForCandidate app "
            + "WHERE app.user.email = :email AND app.resumeData IS NOT NULL "
            + "ORDER BY app.id DESC")
    List<JobApplicationForCandidate> findResumeBearingApplicationsByEmail(@Param("email") String email);

    /**
     * Retrieves all job applications associated with a specific job prefix.
     *
     * @param jobPrefix the prefix identifier of the job
     * @return list of job applications for the job prefix
     */
    @Query("SELECT app FROM JobApplicationForCandidate app WHERE app.jobPost.jobPrefix = :jobPrefix")
    List<JobApplicationForCandidate> findByJobPrefix(@Param("jobPrefix") String jobPrefix);

    /**
     * Retrieves all job applications associated with a specific job post.
     *
     * @param jobPost the JobPost entity
     * @return list of job applications linked to the given job post
     */
    List<JobApplicationForCandidate> findByJobPost(JobPost jobPost);

    /**
     * Retrieves all job applications submitted by a specific user for a job identified by its prefix.
     *
     * @param jobPrefix the prefix identifier of the job
     * @param email the email of the user
     * @return list of matching job applications
     */
    @Query("SELECT j FROM JobApplicationForCandidate j WHERE j.jobPost.jobPrefix = :jobPrefix AND j.user.email = :email")
    List<JobApplicationForCandidate> findByJobPrefixAndEmail(@Param("jobPrefix") String jobPrefix, @Param("email") String email);

    
    Optional<JobApplicationForCandidate> findByJobPost_JobPrefixAndUser_Email(String jobPrefix, String email);

}
